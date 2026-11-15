# Catatan Refactor — Coupon Middleware

## Bug yang diperbaiki
- **Gagal compile**: `SarinahGetModulAdaptor` punya `log.warn(">>> URL: {}", raw n)` — token `raw n` tidak valid. Dihapus, diganti log cuplikan respons yang aman.
- **Mapping hari selalu kosong**: `CouponSyncService` membaca `"days"`, padahal field portal bernama `day_of_week_ids`. Diperbaiki.

## Peningkatan arsitektur
- **Entity diperkaya**: dari 6 field → ~25 field bermakna (sku, disc_percentage, min/max, flag, periode, audit). Tanggal kini `LocalDateTime` (bukan String) → bisa di-query rentang waktu.
- **DTO + Transformer** (`CouponResponse`, `CouponTransformer`): API publik kini mengeluarkan kontrak bersih, TIDAK lagi membocorkan entity mentah / `rawJson` ke pihak ketiga.
- **Query service kembalikan DTO** dan `@Transactional(readOnly=true)`; tambah `getActive()` dan `getValidNow()`.
- **Controller dibersihkan**: endpoint debug `/coba` (yang menerima `@RequestBody ObjectNode` — sumber error Jackson 3) dihapus. Diganti `POST /api/v1/coupons/internal/sync` tanpa body untuk trigger manual.
- **Exception terpusat** (`GlobalExceptionHandler`): error konsisten, tidak membocorkan stack trace.
- **Scheduler dipindah** ke package `scheduler` (pemisahan tanggung jawab).

## Keamanan & kebersihan
- **Dihapus `com.alibaba:fastjson:1.2.47`** — versi dengan celah RCE kritis, dan tidak dipakai di kode mana pun.
- **`spring-jpa.show-sql` → false**, stacktrace dimatikan untuk respons publik.
- Konfigurasi HTTP client dirapikan; trust-all SSL tetap (untuk IP internal self-signed) tapi diberi catatan agar tidak dipakai ke host publik.
- `spring-boot-starter-test` standar menggantikan starter test yang tidak baku.

## Endpoint final
- `GET /api/v1/coupons`            → kupon aktif (DTO)
- `GET /api/v1/coupons/all`        → semua kupon
- `GET /api/v1/coupons/valid-now`  → kupon yang berlaku saat ini
- `GET /api/v1/coupons/{id}`       → detail
- `POST /api/v1/coupons/internal/sync` → trigger sync manual (lindungi di produksi)

## Yang masih bisa ditambah (opsional)
- Entity `CouponSkdLine` (relasi @OneToMany) bila butuh data penggunaan/transaksi kupon dari `skd_line`.
- API Key filter untuk autentikasi pihak ketiga (kerangkanya sudah pernah dibahas).
- Cara enumerasi "semua coupon" bila portal mewajibkan filter name/sku.

---

## Fitur Generate Coupon (POST /api/v1/coupon/generate)

Pemahaman kunci: **kode kupon = `skd` yang dicetak portal**, bukan acak buatan kita.
Setiap panggilan portal mencetak skd baru (terbukti dari `skd_code` & timestamp yang
berubah tiap request). Maka:

- **Kode diambil dari portal**, bukan di-generate sendiri. Service memanggil portal
  (pakai sku campaign), lalu mengambil `skd_code` sebagai `couponCode`.
- **Idempotency jadi kritis**: idempotencyKey yang sama mengembalikan kupon lama
  TANPA memanggil portal lagi → retry tidak mencetak kode ganda.
- **Kolom baru `tag`** (mis. "sarinah-injourney"), diturunkan dari `is_injourney`
  atau dikirim eksplisit di body.
- **`is_used`** disimpan dari skd_line; endpoint listing `GET /api/v1/coupon/generated`
  menyembunyikan kupon yang `is_used = true`.
- **`termsText`** (dari `terms_text`) ditambahkan ke response generate.

Contoh response:
```json
{
  "couponId": 1,
  "couponCode": "2026062499284",
  "status": "ACTIVE",
  "termsText": "tunjukkan kode coupon"
}
```

---

## Perbaikan: idempotency generate untuk request bersamaan

Sebelumnya `generate()` memeriksa `findByIdempotencyKey`, memanggil portal, lalu menyimpan.
Dua request dengan key sama yang datang hampir bersamaan bisa sama-sama lolos pemeriksaan
dan sama-sama memanggil portal sebelum salah satunya ditolak unique constraint.

Sekarang:
- **Tabel klaim baru `coupon_generate_requests`** (PK = `idempotency_key`), dibuat otomatis oleh
  `ddl-auto=update`. Kolom: `request_hash`, `status` (`PROCESSING`/`COMPLETED`/`FAILED`),
  `claim_token`, `locked_until`, `generated_coupon_id`, `attempts`, `last_error`.
- **Klaim atomik sebelum portal**: `INSERT ... ON CONFLICT DO NOTHING` di transaksi sendiri yang
  langsung di-commit. Hanya satu request yang memegang klaim dan memanggil portal.
- **Request lain dengan key sama**:
  - klaim `COMPLETED` → kembalikan kupon yang sama (tanpa memanggil portal);
  - klaim `PROCESSING` dan lease masih berlaku → `409`, client mengulang;
  - klaim `FAILED` atau lease habis → diambil alih secara atomik (`UPDATE ... WHERE`).
- **Simpan hasil secara idempotent**: insert kupon + ubah klaim ke `COMPLETED` dalam satu transaksi,
  dan `COMPLETED` hanya berhasil bila `claim_token` masih milik kita (mencegah pemilik lama yang
  lease-nya habis ikut menyimpan).
- **Gagal sebelum tersimpan** (portal error, kupon habis, kode skd bentrok) → klaim dilepas sebagai
  `FAILED`, sehingga retry dengan key yang sama bisa mencoba lagi.
- **Key dipakai ulang untuk isi berbeda** (couponId/transactionId/tag lain) → `422`.
- `generate()` tidak lagi `@Transactional` penuh: koneksi DB tidak ditahan selama panggilan portal
  (read-timeout bisa 60 detik).
- Kupon lama (sebelum tabel klaim ada) tetap dikenali: bila key sudah punya kupon, klaim langsung
  ditandai `COMPLETED` dan kupon lama dikembalikan.
- Konfigurasi: `coupon.generate.processing-lease=PT2M` (harus > total timeout portal).

---

## Perbaikan: dispatcher webhook aman untuk multi-instance

Sebelumnya `WebhookOutboxDispatcher` membaca event PENDING lalu mengirimnya tanpa lock, sehingga
beberapa instance bisa mengirim event yang sama ke PRIME.

Sekarang:
- Kolom baru `locked_by` dan `locked_until` di `prime_webhook_outbox` (ditambah otomatis oleh
  `ddl-auto=update`, nullable).
- Tiap putaran mengklaim batch secara atomik:
  `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED LIMIT n)`. Instance lain langsung
  mengambil event berikutnya tanpa menunggu, dan satu event hanya dipegang satu instance.
- Lease = `batchSize × (connectTimeout + readTimeout) + 1 menit`; putaran berhenti memulai
  kiriman baru bila lease hampir habis, lalu melepas sisanya.
- Hasil percobaan disimpan dengan `UPDATE ... WHERE id = ? AND locked_by = ?`, sehingga putaran yang
  lock-nya sudah lepas tidak menimpa hasil instance lain.
- Tidak menambah dependency (ShedLock tidak diperlukan) dan tetap paralel antar-instance.

## Perbaikan: GET /api/v1/coupon/code/{code} dengan kode nonnumerik

Saat kupon tidak ditemukan, `GetCouponByCouponCodeService` memanggil `Long.valueOf(couponCode)`
untuk pesan error → `NumberFormatException` → HTTP 500 bila kode berisi huruf. Sekarang memakai
konstruktor `CouponNotFoundException(String)` → HTTP 404 untuk semua bentuk kode, dan kode kosong
→ HTTP 400.

---

## Review keamanan & integritas (putaran 2)

### Akses kupon dibatasi per partner (High)
- Filter API key menyimpan partner ke request attribute `coupon.partnerId`; controller memakai
  partner ini, bukan parameter dari client.
- Kolom baru `generated_coupons.partner_id` dan `coupon_generate_requests.partner_id`.
- `POST /generate` menyimpan partner pada kupon dan klaim idempotency. Key yang sama dari partner
  lain → 422, tidak pernah mengembalikan kupon partner lain.
- `GET /generated`, `GET /{id}`, `GET /code/{code}` hanya membaca kupon milik partner;
  kupon partner lain → 404 / tidak muncul. `tag` hanya menyaring di dalam milik partner.
- **Baris lama (`partner_id` null) tidak terlihat oleh partner mana pun** sampai di-backfill:
  `UPDATE generated_coupons SET partner_id = '<partner>' WHERE partner_id IS NULL AND ...;`
  (sesuaikan kriteria, mis. berdasarkan `tag` atau `transaction_id`). Lakukan hal yang sama
  untuk `coupon_generate_requests` bila retry key lama masih diharapkan berhasil.

### Generate memeriksa kelayakan campaign (High)
- `CampaignEligibility`: `active = true`, `start_date <= sekarang < end_date`, dan hari ini ada di
  `day_of_week_ids` (kosong = setiap hari). Nama hari Inggris/Indonesia dikenali; nama yang tidak
  dikenali → ditolak (fail-closed). Tidak layak → 422, portal tidak dipanggil.
- Template portal dipilih berdasarkan id DAN sku campaign (bukan elemen pertama); `active=false`
  di portal juga ditolak.
- Zona waktu tunggal lewat `PortalTime` (`sarinah-portal.timezone`, default `Asia/Jakarta`) dipakai
  untuk semua perbandingan "sekarang" dan format ISO di response/webhook.

### Respons error portal tidak lagi memicu pencabutan massal (High)
- Adaptor menolak HTTP non-2xx, body kosong, non-JSON, dan object error (`PortalResponseException`,
  di endpoint → 502). Object tunggal hanya diterima bila berbentuk data (punya `id`).
- Sync is_used memvalidasi respons per SKU sebelum mengubah status: sku harus cocok, template harus
  punya `id` dan `skd_line` array yang tidak kosong. Kupon hanya dicocokkan dengan template
  campaign-nya sendiri (`couponId`). Respons tidak valid → SKU dilewati, tidak ada status yang berubah.

### /generated hanya kupon yang benar-benar bisa dipakai (High)
- Filter `status = ACTIVE`, `is_used = false`, dan dalam periode berlaku; response kini memuat `status`.

### Transisi status aman antar-instance sync (Medium)
- Status diubah dengan UPDATE bersyarat (bukan save entity yang dibaca sebelumnya):
  `ACTIVE → REDEEMED|EXPIRED|REVOKED`, `EXPIRED → REDEEMED|REVOKED`, `REVOKED → REDEEMED`,
  `REDEEMED` final. Bila instance lain sudah lebih dulu mengubah kupon, event tidak dibuat.

### Webhook tidak hilang saat dinonaktifkan (Medium)
- Event selalu masuk outbox; `prime.webhook.enabled=false` hanya menahan pengiriman di dispatcher.

### Nominal diskon hanya bila terverifikasi (Medium)
- Fallback "satu-satunya baris minus" dihapus; `discountApplied` hanya diisi bila baris cocok sku
  atau nama kupon.

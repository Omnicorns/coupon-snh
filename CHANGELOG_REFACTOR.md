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

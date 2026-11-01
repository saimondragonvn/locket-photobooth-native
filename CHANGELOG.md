# Lịch sử Phiên bản (Changelog) — Locket Photobooth Native Android

Mỗi lần chỉnh sửa và đẩy code lên GitHub (`git push` hoặc chạy `.\scripts\release-version.ps1`), hệ thống sẽ tự động đánh dấu một **Phiên bản mới (`v1.0.x`)**, ghi nhận danh sách file thay đổi và phát hành gói cài đặt `.apk` riêng biệt tại mục **GitHub Releases** để dễ dàng đối chiếu hoặc quay về bản cũ khi cần.

---

## [v1.0.1] - 2026-11-01
### Khởi tạo bản gốc Native Android 100% (CameraX 60FPS + Media3 GPU)
- **Auth & Multi-Account**: Mã hóa đăng nhập OpenSSL AES-256-CBC (`Salted__` + MD5 `EVP_BytesToKey`), lưu trữ `EncryptedSharedPreferences`, tự động làm mới token 401 bằng Coroutine `Mutex`, chuyển đổi nhanh nhiều tài khoản.
- **Hardware CameraX 1.4 (`60FPS`)**: Tách luồng `PreviewView` (`SurfaceView` `PERFORMANCE`) và `Recorder` H.264/AVC (`2.2 Mbps`), cắt khung vuông `1:1` phần cứng qua `UseCaseGroup` `ViewPort`, hỗ trợ Pinch-to-Zoom, Tap-to-Focus, Flash phần cứng & Ring-Flash màn hình.
- **Media3 GPU Video Processor & Photo Processor**: Cắt `1:1` và áp 10 bộ lọc màu Locket trực tiếp bằng GPU OpenGL (`Crop` + `RgbMatrix`), nén ảnh `< 1 MB` và video `< 5.66 MB` kèm đường tắt 0 giây re-encode cho video quay trực tiếp.
- **Photobooth 4× & Nhãn dán Caption**: Ghép 4 ảnh (`Lưới 2×2`, `Dải đôi`, `Tiêu điểm 1+3`), 8 chế độ nhãn dán (Giờ, Chuỗi Streak, Review 5★, Gradient, Thời tiết GPS, Địa điểm GPS, Mẫu Caption).
- **Room Outbox + WorkManager `dataSync` + 3-Tier Uploader**: Nén và tải ngầm không chặn UI (Tier-1 Direct GCS Signed URL PUT, Tier-2 Resumable 1MB Chunked Upload tự nối mạng, Tier-3 Direct Multipart).
- **Bảng tin Shared `ExoPlayer`, Kỷ niệm, Bạn bè, Chat DM & 14 Chủ đề Locket**.


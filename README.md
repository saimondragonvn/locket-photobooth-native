# Locket Photobooth Native Android (60FPS CameraX + Media3 GPU)

Ứng dụng **Locket Photobooth** thuần **100% Native Android** (Kotlin + Jetpack Compose + CameraX 1.4 + AndroidX Media3 1.4 GPU Transformer), được tái cấu trúc hoàn toàn từ bản web `https://locket-file.vercel.app` để triệt tiêu độ trễ UI, hiện tượng giật/rớt khung hình (drop FPS) và nóng máy khi mở ống kính hoặc quay video.

---

## Điểm nhấn Hiệu năng Phần cứng (Hardware Acceleration)

1. **Tách biệt hoàn toàn luồng Camera & UI (`HardwareCameraController.kt`)**:
   - Hiển thị ống kính bằng `PreviewView.ImplementationMode.PERFORMANCE` (`SurfaceView`) đẩy thẳng frame từ ISP cảm biến lên **Android Hardware Composer (HWC)** ở mức **60FPS** (không vẽ qua WebView hay Canvas UI).
   - Cắt khung vuông **1:1** ngay từ tầng phần cứng qua `ViewPort(Rational(1, 1), rotation)` dùng chung cho `UseCaseGroup(Preview, ImageCapture, VideoCapture)`.
   - Ghi video bằng chip phần cứng **H.264/AVC (`Recorder`)** ở mức `2.2 Mbps` chạy trên `Executors.newFixedThreadPool(2)` tách rời hoàn toàn khỏi Main UI Thread.
2. **Cắt & Nén Video bằng GPU (`Media3VideoProcessor.kt`)**:
   - Dùng `AndroidX Media3 Transformer` với OpenGL shader `Crop` 1:1 + `Presentation` + ma trận màu 4×4 `RgbMatrix` cho toàn bộ **10 bộ lọc màu Locket**.
   - Tự động tính toán bitrate động để video đầu ra nhẹ `< 5.66 MB` (trần `6.0 MB` của Tier-1 Direct GCS) và **bỏ qua re-encode (0 giây chờ)** khi video quay trực tiếp từ CameraX đã đạt chuẩn 1:1 H.264 `< 6 MB`.
3. **Hàng đợi Outbox & Tải lên 3 Tầng chạy ngầm (`OutboxUploadWorker.kt` + `ThreeTierMediaUploader.kt`)**:
   - Bấm **Gửi** trả quyền điều khiển về ống kính trong `< 50ms`.
   - `WorkManager` (`foregroundServiceType="dataSync"`) tự động nén ngầm và chọn tầng tải tối ưu:
     - **Tier 1A / 1B**: `PUT` nhị phân trực tiếp lên **Google Cloud Storage Signed URL** cho ảnh `<= 1 MB` và video MP4 `< 6 MB`.
     - **Tier 2**: Cắt mảnh **1 MB (`1,048,576` bytes) Resumable Chunked Upload** (`/upload-part` + `/upload-part/status`) tự nối lại đúng offset khi rớt mạng 4G/Wi-Fi.
     - **Tier 3**: Direct `MultipartBody` cho ảnh nhỏ trong Kho riêng tư.
4. **Shared Single `ExoPlayer` trên Bảng tin (`MomentFeedScreen.kt`)**:
   - Toàn bộ `VerticalPager` dùng chung **1 instance `ExoPlayer` duy nhất**, chỉ gắn vào trang đang dừng (`settledPage`), ngăn chặn tràn bộ giải mã phần cứng `MediaCodec`.

---

## Hệ thống Quản lý Phiên bản Tự động (Mỗi lần sửa = 1 Phiên bản riêng)

Để luôn biết chính xác bản `.apk` đang dùng chứa những thay đổi nào và dễ dàng quay về bản cũ khi cần:

### Cách A: Phát hành phiên bản mới có ghi chú rõ ràng (Khuyên dùng khi sửa tính năng)
Chạy 1 lệnh duy nhất từ thư mục gốc dự án:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\release-version.ps1 -Message "Mô tả nội dung vừa chỉnh sửa"
```
Script sẽ tự động:
1. Tăng số phiên bản trong `VERSION.txt` (`1.0.1` -> `1.0.2`, hoặc truyền `-Bump minor` / `-Bump major`).
2. Ghi lịch sử thời gian + danh sách file thay đổi vào `CHANGELOG.md`.
3. Tạo Git Commit + Git Tag (`v1.0.2`) và đẩy lên GitHub.
4. Kích hoạt **GitHub Actions** biên dịch gói `Locket-Photobooth-v1.0.2-<commit>-release.apk` và lưu thành **1 bản Release riêng biệt** (kèm link `compare` từng dòng code so với bản trước, tuyệt đối không ghi đè bản cũ).

### Cách B: Push nhanh thông thường (`git commit` + `git push`)
Ngay cả khi chỉ `git push` trực tiếp lên nhánh `main` mà không chạy script, workflow `.github/workflows/build.yml` vẫn tự động cấp số Build mới (`v1.0.<run_number>`), nhúng mã Commit vào màn hình **Hồ sơ** trong ứng dụng và tạo Release riêng kèm danh sách file thay đổi.

---

## Cách Build ra file `.apk` Cục bộ & Cài đặt lên Máy thật

### 1. Build một lệnh trên Windows (PowerShell)
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-apk.ps1
```
Đường dẫn file `.apk` sau khi build xong:
- **Bản Release (Đã ký số, tối ưu R8 `~4.7 MB` mượt nhất — khuyên dùng trên máy thật)**:
  `app\build\outputs\apk\release\app-release.apk`
- **Bản Debug (Cài song song với package `.debug`)**:
  `app\build\outputs\apk\debug\app-debug.apk`

### 2. Cài đặt trực tiếp lên điện thoại qua ADB
```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

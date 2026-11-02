# Lá»‹ch sá»­ PhiĂªn báº£n (Changelog) â€” Locket Photobooth Native Android

Má»—i láº§n chá»‰nh sá»­a vĂ  Ä‘áº©y code lĂªn GitHub (`git push` hoáº·c cháº¡y `.\scripts\release-version.ps1`), há»‡ thá»‘ng sáº½ tá»± Ä‘á»™ng Ä‘Ă¡nh dáº¥u má»™t **PhiĂªn báº£n má»›i (`v1.0.x`)**, ghi nháº­n danh sĂ¡ch file thay Ä‘á»•i vĂ  phĂ¡t hĂ nh gĂ³i cĂ i Ä‘áº·t `.apk` riĂªng biá»‡t táº¡i má»¥c **GitHub Releases** Ä‘á»ƒ dá»… dĂ ng Ä‘á»‘i chiáº¿u hoáº·c quay vá» báº£n cÅ© khi cáº§n.

---

## [v1.0.2] - 2026-11-02 19:27
### Ná»™i dung cáº­p nháº­t
- Dong bo giao thuc v2 (02/11/2026) tu locket-file.vercel.app bang REA: ma hoa dang nhap bat doi xung RSA-OAEP-256 (/login-key & /upgrade-login), tu dong don anh/video loi (MOMENT_GONE & /media-status), cong tac nut Thiep Online & Chuyen tai khoan, Xoa du lieu thiet bi, Dieu khoan 02/11/2026 va bieu tuong chinh thuc
- **Tá»‡p thay Ä‘á»•i**:
  - `.gitignore`
  - `app/src/main/java/com/elingo/locket/MainActivity.kt`
  - `app/src/main/java/com/elingo/locket/core/auth/SessionManager.kt`
  - `app/src/main/java/com/elingo/locket/core/database/LocketDatabase.kt`
  - `app/src/main/java/com/elingo/locket/core/network/LocketApiClient.kt`
  - `app/src/main/java/com/elingo/locket/ui/LocketViewModel.kt`
  - `app/src/main/java/com/elingo/locket/ui/auth/LoginAndAccountsScreen.kt`
  - `app/src/main/java/com/elingo/locket/ui/feed/MomentFeedScreen.kt`
  - `app/src/main/java/com/elingo/locket/ui/profile/ProfileAndSupporterScreens.kt`
  - `app/src/main/java/com/elingo/locket/core/crypto/RsaOaepLoginCipher.kt`
  - `app/src/main/java/com/elingo/locket/ui/legal/`
  - `app/src/main/res/drawable-nodpi/`
## [v1.0.1] - 2026-11-01
### Khá»Ÿi táº¡o báº£n gá»‘c Native Android 100% (CameraX 60FPS + Media3 GPU)
- **Auth & Multi-Account**: MĂ£ hĂ³a Ä‘Äƒng nháº­p OpenSSL AES-256-CBC (`Salted__` + MD5 `EVP_BytesToKey`), lÆ°u trá»¯ `EncryptedSharedPreferences`, tá»± Ä‘á»™ng lĂ m má»›i token 401 báº±ng Coroutine `Mutex`, chuyá»ƒn Ä‘á»•i nhanh nhiá»u tĂ i khoáº£n.
- **Hardware CameraX 1.4 (`60FPS`)**: TĂ¡ch luá»“ng `PreviewView` (`SurfaceView` `PERFORMANCE`) vĂ  `Recorder` H.264/AVC (`2.2 Mbps`), cáº¯t khung vuĂ´ng `1:1` pháº§n cá»©ng qua `UseCaseGroup` `ViewPort`, há»— trá»£ Pinch-to-Zoom, Tap-to-Focus, Flash pháº§n cá»©ng & Ring-Flash mĂ n hĂ¬nh.
- **Media3 GPU Video Processor & Photo Processor**: Cáº¯t `1:1` vĂ  Ă¡p 10 bá»™ lá»c mĂ u Locket trá»±c tiáº¿p báº±ng GPU OpenGL (`Crop` + `RgbMatrix`), nĂ©n áº£nh `< 1 MB` vĂ  video `< 5.66 MB` kĂ¨m Ä‘Æ°á»ng táº¯t 0 giĂ¢y re-encode cho video quay trá»±c tiáº¿p.
- **Photobooth 4Ă— & NhĂ£n dĂ¡n Caption**: GhĂ©p 4 áº£nh (`LÆ°á»›i 2Ă—2`, `Dáº£i Ä‘Ă´i`, `TiĂªu Ä‘iá»ƒm 1+3`), 8 cháº¿ Ä‘á»™ nhĂ£n dĂ¡n (Giá», Chuá»—i Streak, Review 5â˜…, Gradient, Thá»i tiáº¿t GPS, Äá»‹a Ä‘iá»ƒm GPS, Máº«u Caption).
- **Room Outbox + WorkManager `dataSync` + 3-Tier Uploader**: NĂ©n vĂ  táº£i ngáº§m khĂ´ng cháº·n UI (Tier-1 Direct GCS Signed URL PUT, Tier-2 Resumable 1MB Chunked Upload tá»± ná»‘i máº¡ng, Tier-3 Direct Multipart).
- **Báº£ng tin Shared `ExoPlayer`, Ká»· niá»‡m, Báº¡n bĂ¨, Chat DM & 14 Chá»§ Ä‘á» Locket**.


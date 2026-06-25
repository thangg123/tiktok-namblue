# NamBlue Live

App Android TV cực nhẹ để xem live của **namblue**. Mở app ra là một màn đen với **2 nút: TikTok và Facebook** — chọn bằng remote để xem.

- **TikTok** ([@namblueraudua](https://www.tiktok.com/@namblueraudua/live)): phát **native** bằng Media3/ExoPlayer (HLS, fallback FLV). Không WebView, không UI TikTok, không login. Tự resolve URL stream qua API công khai của TikTok. Chưa live / mất mạng thì hiện trạng thái và **tự thử lại** mỗi 10s, có live là vào xem luôn.
- **Facebook** ([facebook.com/namblue/live](https://www.facebook.com/namblue/live)): mở bằng **WebView** (vì FB bắt buộc xem qua trình phát của họ). Có **con trỏ điều khiển bằng remote** giống trình duyệt TV: D-pad di chuyển con trỏ, **OK = bấm**, ra mép trên/dưới thì cuộn trang — để bấm nút **X** đóng popup đăng nhập rồi xem, hoặc đăng nhập 1 lần (cookie được lưu lại).

Chạy được Android 10 (API 29) trở lên. Đăng ký là app **Android TV** (LEANBACK launcher); cài được cả trên điện thoại để test.

## Điều khiển bằng remote

| Màn hình | Phím | Tác dụng |
|---|---|---|
| Màn chọn | ◀ ▶ | Chuyển giữa nút TikTok / Facebook |
| Màn chọn | OK | Mở nguồn đang chọn |
| TikTok | — | Tự phát, không cần thao tác |
| Facebook | ▲▼◀▶ | Di chuyển con trỏ (ra mép trên/dưới = cuộn) |
| Facebook | OK | Bấm tại vị trí con trỏ |
| Bất kỳ | Back | Quay lại màn chọn / thoát |

## Build APK

Mở bằng Android Studio (Open → chọn thư mục này → Sync) rồi Build → Build APK,
hoặc dùng dòng lệnh:

```bash
./gradlew assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk (cài thẳng để test)
./gradlew assembleRelease    # -> APK release đã bật R8/shrink (~1.8MB), cần ký để cài
```

> Lần đầu Gradle sẽ tải AGP/Kotlin/Media3 từ mạng — cần internet.
> Yêu cầu: JDK 17, Android SDK Platform 35 + build-tools 34.0.0.

## Cài lên Android TV

1. Bật **Developer options** → **USB/Network debugging** trên TV.
2. ```bash
   adb connect <IP_TV>:5555      # hoặc cắm USB
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. Mở **NamBlue Live** từ màn hình chính của TV.

## Test

```bash
./gradlew test      # unit test cho TikTok resolver (parse JSON live/offline, chọn quality, chặn body lỗi)
```

## Cấu trúc

```
app/src/main/java/com/namblue/live/
  ChooserActivity.kt      # màn chọn: 2 nút TikTok / Facebook (D-pad)
  PlayerActivity.kt       # player native cho TikTok (PlayerView + vòng lặp resolve/retry)
  TikTokLiveResolver.kt   # api-live/user/room -> room/info -> URL stream (HLS/FLV), fallback HTML
  FacebookWebActivity.kt  # WebView cho FB live + con trỏ điều khiển bằng remote
  LiveStatus.kt           # kết quả resolve: Live(url, type) / Offline / Error
app/src/test/java/...     # unit test resolver
```

## Đổi sang tài khoản khác

- TikTok: sửa hằng `uniqueId` mặc định trong [TikTokLiveResolver.kt](app/src/main/java/com/namblue/live/TikTokLiveResolver.kt) (tên `@`, không có dấu `@`).
- Facebook: sửa `LIVE_URL` trong [FacebookWebActivity.kt](app/src/main/java/com/namblue/live/FacebookWebActivity.kt).

## Lưu ý

- **TikTok** phụ thuộc API live nội bộ của TikTok; nếu TikTok đổi định dạng/chặn vùng thì phần resolve có thể cần chỉnh nhỏ — đã có sẵn fallback HLS→FLV và API→HTML. URL stream có ký + hết hạn nên app luôn resolve lại ngay trước khi phát.
- **Facebook** không cho lấy URL stream live khi chưa đăng nhập (FB phục vụ live qua DASH cuộn của trình phát họ), nên đi qua WebView là cách ổn định nhất. Lần đầu có thể phải bấm **X** đóng popup hoặc đăng nhập (quét QR bằng điện thoại cho nhanh) — sau đó cookie được lưu.
- Mục đích sử dụng cá nhân.
```

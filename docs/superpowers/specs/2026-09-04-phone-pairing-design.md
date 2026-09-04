# Ghép nối từ điện thoại (phone pairing) — thiết kế

**Ngày:** 2026-09-04
**Trạng thái:** đã duyệt qua thảo luận trong chat (payload, thư viện, thiết kế 6 mục); file này là bản ghi để viết plan.

## 1. Mục tiêu

API key dài hàng chục ký tự, gõ trên bàn phím e-ink gần như bất khả thi. Người dùng cần một cách đưa key (và base url, model) từ điện thoại — nơi key thật sự nằm trong password manager hoặc email của provider — sang BOOX mà không cần camera trên BOOX, không cần backend, không cần cáp.

Cách làm: BOOX hiện một QR chứa URL tới một HTTP server nhỏ chạy ngay trong app trên Wi-Fi nội bộ. Điện thoại quét, mở trang web đó, dán key, bấm gửi. BOOX nhận, điền vào form Settings; người dùng bấm save như bình thường.

## 2. Ngoài phạm vi

- Không HTTPS: cert tự ký làm trình duyệt điện thoại chặn trang, phá tan mục đích. Key đi qua HTTP thường trên Wi-Fi nội bộ; trang ghép nối ghi rõ điều này.
- Không ghép nối qua cloud / mã pairing kiểu TV app: cần backend, trái tư thế keyless, không server của dự án.
- Không quét QR trên BOOX: hầu hết máy không có camera.
- Không lưu key tự động: `PairActivity` chỉ trả kết quả về form Settings; đường lưu vẫn là `save()` hiện có với đầy đủ validate (cảnh báo `/v1` thừa…).
- Không sinh danh sách model trên trang điện thoại: model là ô text tuỳ chọn; để trống thì giữ model đang có trên BOOX, chọn model vẫn dùng chooser trên BOOX (`pickModel()` hỏi server, không phải gõ).
- Không giữ server chạy nền: chỉ sống khi `PairActivity` resumed.

## 3. Luồng người dùng

1. Settings → mục "AI model" → hàng mới "set up from phone" (chooser field, ngay dưới "model").
2. `PairActivity` mở: nếu không lấy được IP LAN, hiện dòng "connect this diary to Wi-Fi first" và không start server. Có IP: start server cổng ngẫu nhiên, vẽ QR to, in URL dạng chữ bên dưới (gõ tay được nếu quét hỏng), dòng trạng thái "waiting for your phone…".
3. Điện thoại quét, mở `http://<ip>:<port>/?t=<token>`. Trang: radio provider (OpenAI / OpenRouter / other + ô url hiện khi chọn other), ô api key, ô model (tuỳ chọn), nút "send to my diary". Có dòng "works on your home Wi-Fi only".
4. Gửi hợp lệ: trang điện thoại hiện "sent, look at your diary". Server dừng. `PairActivity` trả `RESULT_OK` với ba extra rồi `finish()`.
5. Settings nhận result: gán `chosenProvider`/`customBaseUrl`, `apiKeyField`, `chosenModel` (nếu không trống), gọi `showBaseUrl()` và cập nhật `modelField`. Form dirty; người dùng bấm save.

## 4. Kiến trúc

Tất cả đặt phẳng trong `com.riddleboox.app.settings` (cùng package với `SettingsActivity`, `Providers.kt`), theo convention flat của repo.

```
app/src/main/java/com/riddleboox/app/settings/
├── PairingPayload.kt   # data class + parsePairing(form): pure
├── PairingPage.kt      # pairingPage(token, providers): String HTML, pure
├── LanAddress.kt       # pickLanAddress(addresses): pure + lanAddress(context): shell
├── QrBitmap.kt         # qrBitmap(text, sizePx): Bitmap (zxing encode → đen/trắng)
├── PairingServer.kt    # bọc ktor embeddedServer(CIO): start/stop, port, callback
└── PairActivity.kt     # UI giấy: QR, URL, trạng thái; lifecycle server

app/src/test/java/com/riddleboox/app/settings/
├── PairingPayloadTest.kt
├── PairingPageTest.kt
├── LanAddressTest.kt
├── PairingServerTest.kt   # JVM thuần, server thật trên cổng ngẫu nhiên
├── QrBitmapTest.kt        # Robolectric @Config(sdk = [35])
└── (bổ sung) SettingsActivityPairingTest.kt  # Robolectric: result → form
```

Dependency mới trong `app/build.gradle.kts`:

- `io.ktor:ktor-server-cio:3.3.3` — cùng phiên bản ktor mà koog đang kéo (`ktor-http-cio`, `ktor-network` 3.3.3 đã có trên classpath); dùng `embeddedServer(CIO, ...)` tường minh, không đi qua ServiceLoader.
- `com.google.zxing:core:3.5.4` — chỉ encode, không camera.

Manifest: `PairActivity` với `android:exported="false"`, `configChanges` giống các activity khác.

### 4.1 `PairingPayload.kt` (pure)

```kotlin
data class PairingPayload(val baseUrl: String, val apiKey: String, val model: String)

sealed interface PairingParse {
    data class Ok(val payload: PairingPayload) : PairingParse
    data class Rejected(val reason: String) : PairingParse
}

fun parsePairing(form: Map<String, String>): PairingParse
```

Quy tắc: `provider` là một trong các `PROVIDERS[i].label` hoặc `"other"`; `other` bắt buộc `base_url` không trống; `api_key` trim, không trống; `model` trim, có thể trống. Trả `Rejected` kèm lý do hiển thị được trên trang điện thoại.

### 4.2 `PairingPage.kt` (pure)

`pairingPage(token: String, providers: List<Provider>): String` — HTML tự chứa, không CDN, form `POST /pair` với hidden `t`, một đoạn script nhỏ ẩn/hiện ô url khi chọn other. `confirmationPage(): String` và `rejectedPage(reason): String` cho phản hồi. Escape HTML cho mọi chuỗi động.

### 4.3 `LanAddress.kt`

`pickLanAddress(addresses: List<InetAddress>): Inet4Address?` — ưu tiên IPv4 site-local, bỏ loopback và link-local. `lanAddress(context): Inet4Address?` đọc `ConnectivityManager.activeNetwork` → `getLinkProperties().linkAddresses` (quyền `ACCESS_NETWORK_STATE` đã có).

### 4.4 `PairingServer.kt`

```kotlin
class PairingServer(private val token: String, private val onPaired: (PairingPayload) -> Unit) {
    fun start(): Int          // bind 0.0.0.0, port 0 → trả cổng thật
    fun stop()                // blocking, grace ngắn
}
```

Route:

- `GET /?t=<token>` → 200 `pairingPage`; token sai/thiếu → 403 rỗng.
- `POST /pair` (form-urlencoded, có `t`) → token sai → 403; `parsePairing` Rejected → 400 `rejectedPage`; Ok → 200 `confirmationPage`, gọi `onPaired` (thread của ktor), sau đó server tự dừng (mỗi phiên nhận đúng một lần).
- Mọi response: `Cache-Control: no-store`.

### 4.5 `PairActivity.kt`

- `onResume`: sinh token 128-bit (`SecureRandom`), lấy IP; có IP thì `PairingServer.start()`, dựng URL, vẽ QR (một lần, vào `ImageView`, không animation).
- `onPause`: `stop()`.
- `onPaired` → `runOnUiThread` → kiểm tra `isFinishing || isDestroyed` → `setResult(RESULT_OK, intent với EXTRA_BASE_URL/EXTRA_API_KEY/EXTRA_MODEL)` → `finish()`.
- Companion: `intent(context)`, hằng extra, `REQUEST_PAIR` dùng bên Settings.

## 5. Bảo mật

- Token ngẫu nhiên mỗi lần mở màn hình; bắt buộc khớp ở cả GET và POST.
- Cổng chỉ mở khi màn hình ghép nối đang hiện; dừng ngay sau một POST hợp lệ.
- Key không log; trang có `no-store`.
- Không thêm quyền mới.

## 6. Test

- JVM thuần: `parsePairing` (thiếu key, other thiếu url, trim, model trống, provider lạ), `pairingPage` chứa đủ label provider và token, `pickLanAddress` ưu tiên site-local và bỏ loopback/link-local, `PairingServer` chạy thật: GET đúng token 200, sai token 403, POST hợp lệ gọi callback đúng payload và cổng đóng sau đó.
- Robolectric `@Config(sdk = [35])`: `qrBitmap` đúng kích thước và ô trái trên đen; `SettingsActivity` nhận result → ba trường đổi, `dirty()` true.
- Máy thật (debug build): quét QR, gửi từ điện thoại, key vào form, save thành công. Bước nào chưa chạy được sẽ ghi rõ trong summary.

## 7. Tài liệu

README mục `SettingsActivity` thêm "set up from phone"; bẫy mới (nếu có) ghi `CLAUDE.md`.

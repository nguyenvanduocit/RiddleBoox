# Deep Research: Viết app cho máy đọc sách BOOX (Onyx BOOX)

> Nghiên cứu thực hiện ngày 2026-08-19. Mọi claim kèm nguồn tham khảo và mức tin cậy
> (HIGH = xác nhận từ tài liệu chính thức; MEDIUM = suy từ pattern; LOW = guess cần verify).

## 1. Nền tảng BOOX là gì

BOOX = máy e-reader/e-note chạy **Android** (không phải hệ điều hành đóng như Kindle/Kobo).
**Viết app cho BOOX = viết Android app**, rồi cài qua Google Play hoặc sideload APK.
*(HIGH — Reddit, eWritable, awesome-boox)*

- **Hệ điều hành**: Android tùy model — từ Android 6 (đời cũ) đến **Android 11/12/13** (đời mới, ví dụ Leaf 5 chạy Android 13 theo MobileRead).
- **Màn hình**: E-Ink Carta / Kaleido (color) / Gallery. Khác biệt cốt lõi: **tốc độ refresh rất thấp, chỉ 16-level grayscale (256 cho một số asset), bị ghosting**. *(HIGH — deepwiki EPD system)*
- **Phần mềm có sẵn**: **NeoReader** (đọc EPUB/PDF nội bộ, có highlight, ghi chú, từ điển), Notebook (note + chữ viết tay), BooxDrop (truyền file qua browser).
- **Chip**: RK/Rockchip hoặc Snapdragon — cấu hình yếu hơn tablet thường.

## 2. Lựa chọn kiến trúc

| Kiến trúc | Độ phù hợp | Ghi chú |
|---|---|---|
| **Native Android + Onyx SDK** | ⭐⭐⭐⭐⭐ | Trải nghiệm tốt nhất, truy cập toàn bộ API điều khiển màn hình E-Ink |
| **Hybrid WebView (Capacitor/TWA)** | ⭐⭐⭐ | Tái dùng được Vue frontend, nhưng phải đối phó ghosting/scroll; có `setWebViewContrastOptimize` để giảm |
| **Web app trong browser** | ⭐⭐ | Chạy được (BOOX có browser Chrome-based), nhưng không có quyền điều khiển EPD, UX xấu với SPA |
| **Flutter** | ⭐⭐ | Có plugin `onyxsdk_pen` cộng đồng, nhưng SDK chính thức (EPD/WebView optimize) là Java/Kotlin |

*(MEDIUM-HIGH — framework map suy từ tài liệu chính thức)*

## 3. Onyx SDK — bắt buộc phải biết

Onyx phát hành SDK chính thức tại `github.com/onyx-intl/OnyxAndroidDemo` (219★, cập nhật Aug 2026).
Ba SDK: *(HIGH — README repo)*

- `onyxsdk-base` — lõi: `EpdController`, `FrontLightController`, `DeviceEnvironment`, `DeviceUtils`
- `onyxsdk-pen` — stylus low-latency (`TouchHelper`)
- `onyxsdk-scribble` — ghi/lưu nét vẽ

Thêm vào `build.gradle`:

```groovy
implementation('com.onyx.android.sdk:onyxsdk-device:1.1.11')
implementation('com.onyx.android.sdk:onyxsdk-pen:1.2.1')
repositories { maven { url "http://repo.boox.com/repository/maven-public/" } } // + jitpack
```
*(HIGH — README)*

### 3.1 Refresh modes (UpdateMode) — trái tim của E-Ink

Màn hình EPD cần waveform đặc biệt để đổi trạng thái — trade-off giữa tốc độ và chất lượng (ghosting).
*(HIGH — doc EPD-Update-Mode)*

| Mode | Chất lượng | Dùng cho |
|---|---|---|
| `GU` | partial update, cân bằng | cập nhật 1 phần |
| `GC` | full-screen, sạch ghosting, chậm | chuyển trang sách |
| `DU` / `DU_QUALITY` | nhanh, đen/trắng | — |
| `REGAL` | partial update chất lượng cao, ít ghosting | **text** |
| `ANIMATION`/`ANIMATION_QUALITY` | rất nhanh (A2 mode) | animation, drag |
| `ANIMATION_X` | nhanh nhất, mất chi tiết | video/web browsing |

Cấp độ cao hơn: `UpdateOption` preset (NORMAL, FAST_QUALITY, REGAL, FAST, FAST_X) = "Speed Modes"
trong Settings; áp theo scope app qua `Device.currentDevice().setAppScopeRefreshMode()`.

Quản lý ghosting: `EpdDeviceManager.setGcInterval(n)` — tự động full-refresh sau n lần partial update.
Full-refresh: `EpdController.repaintEveryThing(UpdateMode.GC)`.

### 3.2 Tối ưu WebView

- `EpdController.setWebViewContrastOptimize(webView, true)` — tăng contrast content web, chữ sắc nét hơn, bỏ nền xám gây ghosting. *(HIGH — WebViewOptimizeActivity)*
- App có thể set CTP disable region (tắt touch 1 vùng — palm rejection khi đọc).
- Firmware 3.2+ có **EAC (Enhanced App Compatibility)**: `SimpleEACManage` — bật/tắt tối ưu cho app thứ ba, quản lý refresh config, `setFollowSystemRotation`.

### 3.3 Cảnh báo SDK — quan trọng

- SDK chỉ chạy trên thiết bị BOOX thật; app sẽ **crash trên máy không phải BOOX** (dev Tools-for-Boox trên Reddit/F-Droid).
- Một số API (RawDrawing, ViewUpdateHelper) **không hoạt động trên đời mới** (Go 10.3) — Stack Overflow 2025. *(MEDIUM — cần verify trên model cụ thể)*
- Cần `adb` để cài/test (`AppOpenGuide.md`).

## 4. E-Ink UI/UX Guidelines (tài liệu chính thức của Onyx)

Từ `doc/Eink-Develop-Guide.md`: *(HIGH — nguyên văn)*

1. **Dùng đen trắng / 16-level grayscale** làm màu chính; màu đổi trạng thái phải nằm trong 256-level grayscale.
2. **KHÔNG dùng layer trong suốt** lên ảnh/text (gây ghosting + artifact).
3. **Dùng page-based loading**, không scroll/drag liên tục.
4. **Tránh animation** (scrolling, dragging, transition).
5. **Font ≥ 14sp**, ưu tiên bold/heiti (sans-serif).
6. **Touch target**: giữa màn ≥ 36×36dp, mép ≥ 48×48dp.
7. Dùng `DeviceUtils.setFullScreenOnResume()` để ẩn status bar.

Hệ quả trực tiếp: **SPA hiện đại (Vue/React) với transition, smooth scroll, infinite scroll là
anti-pattern** trên E-Ink — nếu giữ web app thì phải page-based navigation và tắt hết animation.

## 5. Phân phối app trên BOOX

- **Google Play**: model quốc tế đời mới (2022+) có Play Store cài sẵn; model cũ hoặc bản khu vực
  cần kích hoạt thủ công (Settings → App management → Enable Google Play → GSF ID).
  *(HIGH — help.boox.com, macmyths, mobileread)*
- **Sideload APK**: luôn được — copy APK vào máy, bật "install unknown sources". Kênh phổ biến:
  F-Droid → Aurora Store (tải APK từ Play không cần login Google). *(HIGH — Reddit)*
- **Onyx Store**: có store riêng, nhưng quy trình nộp app third-party **không công khai rõ** —
  dev thường phát hành qua Play + GitHub Releases + sideload. *(MEDIUM-LOW)*
- Không có gating/review như Apple; có thể phát hành APK trực tiếp.

## 6. Dữ liệu cho RiddleBoox (app nhật ký AI viết tay kiểu Riddle trên BOOX)

Kết hợp Riddle (reMarkable) + Inka (BOOX-native AI journal). Điểm khác biệt then chốt so với Riddle gốc:

1. **Riddle** dùng `libqsgepaper.so` (vendor waveform engine của reMarkable) qua driver `quill`,
   bút evdev 4096-level pressure. BOOX có SDK tương đương: **`onyxsdk-pen` / `TouchHelper`**
   cho stylus low-latency + `EpdController` cho refresh. *(HIGH — OnyxAndroidDemo + riddle README)*
2. **Hình thức app**: Riddle chạy "takeover" thay xochitl; BOOX chạy như Android Activity bình thường —
   đơn giản hơn nhiều, không cần đụng vendor engine. *(MEDIUM — suy từ kiến trúc Android)*
3. **Inka** (BoltAI/Inka): app native Android AI journal cho BOOX Note Air — mẫu tham chiếu trực tiếp
   về cách một app AI chạy nền tảng BOOX (cài qua Play/sideload, dùng EMR stylus).
4. **Refresh strategy gợi ý cho hiệu ứng "ink được viết tay từng nét"**:
   - Nét bút đang vẽ → `ANIMATION_QUALITY` (A2) — nhánh nhanh, ít detail.
   - Reply "tự viết" theo nét → partial update `REGAL` + `setGcInterval` để tránh tích ghosting.
   *(MEDIUM — suy từ bảng UpdateMode)*
5. **LLM**: Riddle gọi vision LLM (OpenAI-compatible, đọc PNG trang viết tay). BOOX có Wi-Fi + camera ít
   hơn; nên giữ nguyên pipeline: rasterize trang → PNG → gửi LLM → stream reply từng câu → hiện lại
   dạng nét bút. *(HIGH — riddle README)*

## 7. Nguồn tham khảo

- GitHub `onyx-intl/OnyxAndroidDemo` — SDK + demo + doc: https://github.com/onyx-intl/OnyxAndroidDemo
- DeepWiki tóm tắt repo trên: https://deepwiki.com/onyx-intl/OnyxAndroidDemo
- Flutter plugin `onyxsdk_pen`: https://pub.dev/packages/onyxsdk_pen
- Riddle (reMarkable, Rust): https://github.com/MaximeRivest/Riddle
- Inka (BOOX native AI journal): https://github.com/BoltAI/Inka
- App tham khảo cộng đồng khác: `steffest/Boox-EinkDraw`
- Cài app/Google Play trên BOOX: help.boox.com, macmyths.com, Reddit r/Onyx_Boox
- BOOX blog tối ưu app thứ ba: https://shop.boox.com/blogs/news/optimize-onenote-evernote-wps
- Danh sách tài nguyên cộng đồng: https://github.com/emory/awesome-boox

## Giới hạn của research

Chưa có máy BOOX thật để test runtime. Các claim về crash, RawDrawing trên model mới là từ báo cáo
cộng đồng — cần verify trên model cụ thể khi bắt đầu code.
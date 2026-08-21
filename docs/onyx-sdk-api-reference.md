# Onyx SDK — API reference đã xác minh (cho RiddleBoox)

> Nghiên cứu 2026-08-19, đọc trực tiếp `references/OnyxAndroidDemo/doc/` (19/21 file) +
> đối chiếu code thật của Inka. Citation dạng `doc-file:line`. HIGH = đọc trực tiếp doc/code;
> LOW/MEDIUM ghi rõ.

## 1. TouchHelper — nhận nét bút low-latency (`onyxsdk-pen:1.4.11`)

- Init: `TouchHelper.create(view, callback).setStrokeWidth(3.0f).setLimitRect(limit, exclude).openRawDrawing()`
  (`Onyx-Pen-SDK.md:19-27`). `callback` = `RawInputCallback`; `limit` = Rect vùng vẽ; `exclude` = List<Rect>.
- `setRawDrawingEnabled(true)` — vào scribble mode, **"the screen will not refresh"** (framework tự
  render nét lên EPD, app không cần invalidate) (`Onyx-Pen-SDK.md:30,96`); `(false)` pause.
- `setRawDrawingRenderEnabled(false)` — tắt render framework, app tự vẽ từ callback (`Onyx-Pen-SDK.md:32,97`).
- `closeRawDrawing()` — release + **unlock screen** (`Onyx-Pen-SDK.md:36,99`).
- `setStrokeStyle(int)`: `STROKE_STYLE_FOUNTAIN` / `STROKE_STYLE_PENCIL` (`Onyx-Pen-SDK.md:100`).
- Callbacks (thứ tự): `onBeginRawDrawing()` → `onRawDrawingTouchPointMoveReceived()` →
  `onRawDrawingTouchPointListReceived()` → `onEndRawDrawing()`; eraser tương tự `onRawErasing*`
  (`Onyx-Pen-SDK.md:40-41`). **Nhấc bút = `onEndRawDrawing(boolean b, TouchPoint)`**
  (`Onyx-Pen-SDK.md:53,62`). Ý nghĩa `boolean b` không được doc — LOW.
- `BrushRender.drawStroke(Canvas, Paint, List<TouchPoint>, strokeWidth, EpdController.getMaxTouchPressure())`
  — vẽ nét có pressure nếu app tự render (`Onyx-Pen-SDK.md:112-114`).
- Màu: chỉ đen `0xff000000` / trắng `0xffffffff` (`Scribble-API.md:37-39`) — đúng nhu cầu RiddleBoox.
- Không có doc về thread callback / tọa độ tuyệt đối — MEDIUM, xem demo code khi scaffold.

## 2. EpdController — refresh E-Ink

- `EpdController.setViewDefaultUpdateMode(view, UpdateMode.GU)` — partial mặc định (`EPD-Screen-Update.md:6`).
- `EpdController.setViewDefaultUpdateMode(view, UpdateMode.REGAL)` — tối ưu text (`EPD-Screen-Update.md:12`).
- `EpdController.invalidate(view, UpdateMode.GC)` — full-screen update (`EPD-Screen-Update.md:18`).
- `EpdController.applyApplicationFastMode(APP, true, clear)` / `(false, clear)` — vào/thoát fast mode
  (A2) cho zoom/scroll/drag (`EPD-Screen-Update.md:26-30`).
- `EpdController.setWebViewContrastOptimize(WebView, boolean)` (`EpdController.md:13`).
- `EpdController.getMaxTouchPressure()` (`Onyx-Pen-SDK.md:112`).
- Touch: `setAppCTPDisableRegion(Context, Rect[])` + variants, `appResetCTPDisableRegion(Context)`
  (`EPD-Touch.md:15-68`) — palm rejection.
- **UpdateMode enum đầy đủ** (`EPD-Update-Mode.md:25-62`): `DU`, `DU_QUALITY`, `GU`, `GU_FAST` (deprecated),
  `GC`, `GC_4` (deprecated), `ANIMATION` (black/white, nhanh hơn DU, chất lượng thấp — chỉ draw/scroll),
  `ANIMATION_QUALITY`, `REGAL`, `REGAL_D`. **Không có `ANIMATION_X`** trong enum này (cái có `FAST_X`
  thuộc enum khác `UpdateOption` NORMAL/FAST_QUALITY/REGAL/FAST/FAST_X — `EPD-Update-Mode.md:3-21`).
- **API `EpdController.refreshScreen(UpdateMode)` + `UpdateMode.HAND_WRITING_REPAINT_MODE` + `DEEP_GC`
  KHÔNG có trong doc** nhưng Inka dùng thật (`page/EinkRefresher.kt:5-54`) với onyxsdk-pen 1.5.4 —
  HIGH (bằng chứng code chạy thật trên BOOX), cần xác nhận phiên bản SDK khi scaffold.

## 3. EpdDeviceManager — wrapper refresh

- `setGcInterval(int)` — "set partial and full screen update intervals" (`EpdDeviceManager.md:6`);
  đơn vị không ghi — LOW.
- `applyWithGCInterval(textView, isTextPage)` — tự chọn partial/REGAL theo device (`EpdDeviceManager.md:12,15`).
- `applyGCUpdate(textView)` — ép full update (`EpdDeviceManager.md:19-20`).
- `enterAnimationUpdate(true)` / `exitAnimationUpdate(true)` — fast mode (`EpdDeviceManager.md:26-28`).

## 4. Eink-Develop-Guide — 6 quy tắc chính thức

1. Đen trắng 16-level gray chủ đạo; màu trạng thái trong 256-level (`Eink-Develop-Guide.md:8-9`).
2. Không layer transparent lên ảnh/text (`:10`).
3. Page-based loading (`:11`).
4. Tránh animation/scroll liên tục (`:12`).
5. Font ≥ 14sp; embed font thì bold (`:13-14`).
6. Touch target: giữa ≥ 36×36dp, mép ≥ 48×48dp (`:15`).

## 5. Gradle & phân phối

- `onyxsdk-base:1.4.3.7` (`Onyx-Base-SDK.md:3-5`), `onyxsdk-pen:1.4.11` (`Onyx-Pen-SDK.md:14`),
  `onyxsdk-scribble:1.0.8` + bắt buộc `maven { url "https://jitpack.io" }` (`Onyx-Scribble-SDK.md:7-10`).
- Maven Onyx thực tế (từ Inka): `http://repo.boox.com/repository/maven-public/` (`references/Inka/settings.gradle.kts:15-17`) — HIGH.
- `AppOpenGuide.md` không phải hướng dẫn gradle — chỉ lệnh adb `am start -n <component>` (`AppOpenGuide.md:1-16`).
- Doc không có minSdk — lấy từ demo code khi scaffold.

## 6. Sửa lỗi research trước (docs/boox-app-development-research.md)

Các claim cũ **KHÔNG tồn tại trong doc hoặc demo code** (đã grep toàn bộ `doc/` + `app/src`):

| Claim cũ (mục 3.x) | Thực tế |
|---|---|
| `EpdController.repaintEveryThing(GC)` | Không có trong doc — thay bằng `invalidate(view, GC)` hoặc `refreshScreen(GC)` (Inka) |
| `Device.currentDevice().setAppScopeRefreshMode()` | Không có trong doc |
| `EpdDeviceManager.setAppScopeOptimizeEffect` | Không có trong doc |
| `UpdateMode.ANIMATION_X` | Không có — dùng `ANIMATION`/`ANIMATION_QUALITY` hoặc `UpdateOption.FAST_X` |
| `DeviceUtils.setFullScreenOnResume()` | Không có trong doc (có thể có trong jar SDK — LOW) |
| Doc có 7 quy tắc Eink | Thực tế **6** quy tắc (không có quy tắc stroke width) |

## 7. API mapping cho RiddleBoox (đề xuất thiết kế)

1. **Nhận nét bút**: `TouchHelper.create(view, callback).openRawDrawing()` + `setRawDrawingEnabled(true)`
   — framework tự vẽ nét lên EPD, latency thấp nhất; app chỉ lưu stroke từ callback. Nhấc bút =
   `onEndRawDrawing` → bắt đầu idle timer (thay evdev của Riddle).
2. **Vẽ nét tức thì**: raw drawing mode (screen tự khóa refresh) — đúng vai trò "quill" của Riddle;
   không gọi thêm refresh nào khi đang vẽ.
3. **Reply tự viết**: render từng nét con vào View (canvas), refresh theo stroke bằng
   `invalidate(view, DU)` (nhanh, đen/trắng) hoặc `UpdateMode.HAND_WRITING_REPAINT_MODE` (xác nhận
   version SDK với Inka 1.5.4).
4. **Text reply khi dừng**: `setViewDefaultUpdateMode(view, REGAL)` hoặc `applyWithGCInterval(view, true)`.
5. **Chống ghosting**: `EpdDeviceManager.setGcInterval(...)` + ép `applyGCUpdate` sau khi reply fade
   (thay `full_refresh` của Riddle `main.rs:736`).
6. **Palm rejection**: `setAppCTPDisableRegion` khóa vùng toolbar khi vẽ.
7. **Cô lập SDK** như Inka: đúng 2 class (`InkCaptureController` + `EinkRefresher`) + fallback MotionEvent.

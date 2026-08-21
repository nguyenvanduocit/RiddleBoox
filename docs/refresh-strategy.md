# Refresh strategy — thiết kế (item 5 trong README)

> Tổng hợp từ `riddle-pipeline-analysis.md`, `inka-architecture-analysis.md`, `onyx-sdk-api-reference.md`.
> State machine của Riddle (`Listening→Drinking→Thinking→Replying→Lingering→FadingReply`) giữ nguyên,
> chỉ thay tầng hiển thị bằng API Onyx. Confidence: MEDIUM-HIGH (suy từ doc + code Inka chạy thật;
> cần test runtime trên máy BOOX thật).

## Nguyên tắc

- **Đang vẽ**: không refresh thủ công gì — raw drawing mode của TouchHelper tự khóa màn hình và render
  nét trực tiếp lên EPD (latency thấp nhất, đúng vai "quill" của Riddle). (*HIGH — Onyx-Pen-SDK.md:30,96*)
- **Chỉ refresh khi trạng thái màn hình đổi** (xoá mực, hiện reply, fade) — mỗi thay đổi = 1 pass
  `invalidate(view, mode)`/`refreshScreen(mode)`.
- **Text reply = REGAL/GC**; hiệu ứng chuyển động = DU/ANIMATION (nhanh, chấp nhận ghosting tạm thời);
  cuối mỗi chu kỳ = **ép GC full** dọn ghosting.

## Invariant khi chuyển view

Đây là quy tắc bắt buộc cho `MainActivity` và các màn hình mở bằng
`startActivityForResult`:

- Settings chỉ trả `RESULT_OK` sau khi lưu; Back hoặc huỷ không được gọi
  `MainActivity.recreate()`. Recreate khi chỉ quay lại sẽ tạo `SurfaceView` mới,
  reset state machine và gây full refresh ngoài ý muốn.
- `EinkRefresher.configureNewSurfaces()` phải chạy trước khi tạo `SurfaceView`,
  để Onyx SDK không tự phát sinh GC refresh khi surface mới được attach.
- `surfaceChanged()` chỉ được phát một full refresh cho mỗi surface mới. Các
  callback lặp lại cho cùng surface chỉ cập nhật giới hạn pen, không flash lại.
- Khi thực sự lưu Settings, một lần recreate và một full refresh có chủ đích là
  chấp nhận được để nạp cấu hình LLM mới. Luồng này đã được smoke-test trên
  BOOX Note Air 2.

Code tham chiếu: `app/src/main/java/com/riddleboox/app/MainActivity.kt` và
`app/src/main/java/com/riddleboox/app/settings/SettingsActivity.kt`.

## Map state → refresh (thay bảng hiện tại README)

| State (Riddle) | Hành động | Refresh | Ghi chú |
|---|---|---|---|
| **Listening** (đang vẽ) | TouchHelper raw drawing | framework tự render, không refresh | `setRawDrawingEnabled(true)`; `setPenUpRefreshEnabled(true)` (Inka `InkCaptureController.kt:351`) |
| **Drinking** (mực biến mất, 14×70ms) | mỗi dissolve stage vẽ lại canvas | `invalidate(view, DU)` per stage | DU đen/trắng nhanh — phù hợp dissolve đơn sắc; DEEP_GC một lần sau khi clear (Inka dùng DEEP_GC cho full clean) |
| **Thinking** (chấm pulse 600ms) | toggle chấm | `invalidate(view, DU)` hoặc `ANIMATION` | vùng nhỏ — partial đủ |
| **Replying** (tự viết, 14ms/26 điểm) | vẽ nét con từng tick | `invalidate(view, HAND_WRITING_REPAINT_MODE)` (Inka) hoặc `DU` | batch điểm theo tick như Riddle, không refresh từng điểm lẻ |
| **Lingering** (giữ reply 4-20s) | không refresh | — | |
| **FadingReply** (10×80ms) | dissolve + `applyGCUpdate` cuối | `invalidate(view, DU)` per stage, **cuối: `refreshScreen(GC)`/`DEEP_GC`** | GC cuối = chống ghosting (thay `full_refresh` Riddle `main.rs:736`) |

## Chống ghosting

- `EpdDeviceManager.setGcInterval(N)` — framework tự full-refresh sau N partial updates (`EpdDeviceManager.md:6`;
  đơn vị cần xác nhận).
- Reply dài nhiều nét → mỗi N nét hoặc mỗi dòng: `applyGCUpdate(textView)` (`EpdDeviceManager.md:19-20`).
- Conjure (replay memory, màu FADED): sau khi replay xong → GC một lần.

## Palette

- Nét user: đen `0xff000000` (TouchHelper chỉ hỗ trợ đen/trắng — đúng).
- Reply + conjure: đen hoặc FADED 0x7BCF (Riddle dùng grayscale 16-level — trong giới hạn Eink guide).

## Việc cần verify trên máy thật

1. `HAND_WRITING_REPAINT_MODE` + `refreshScreen(GC)` có trên SDK version nào (Inka dùng pen 1.5.4) —
   doc cũ 1.4.11 không có.
2. `setGcInterval` đơn vị (ms? lần?).
3. Độ trễ thực của `invalidate(DU)` vs `ANIMATION` khi dissolve — timing Riddle (70-80ms/stage) giữ hay
   tăng.
4. `setPenUpRefreshEnabled(true)` — refresh sau pen-up có gây giật nét cuối không.

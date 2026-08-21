# Inka — phân tích kiến trúc (mẫu tham chiếu BOOX Android)

> Nghiên cứu 2026-08-19, đọc trực tiếp `references/Inka/`. All claims HIGH (đọc trực tiếp code)
> trừ chỗ ghi chú. Đây là ví dụ sống duy nhất: **app AI journal native chạy thật trên BOOX
> Note Air với đầy đủ Onyx SDK** — gần nhất với RiddleBoox.

## 1. Stack

- Kotlin 100%, **classic Views (KHÔNG Compose)** — `MainActivity` extends `ComponentActivity`, UI
  programmatic (SurfaceView/FrameLayout) (`ui/MainActivity.kt:8-11`).
- minSdk 29, target/compile 35 (`app/build.gradle.kts:23,32-35`).
- Deps chính (`app/build.gradle.kts:101-115`): **`com.onyx.android.sdk:onyxsdk-pen:1.5.4`**,
  `com.google.mlkit:digital-ink-recognition` (nhận dạng chữ viết tay), **okhttp 4.12 + kotlinx-serialization
  (KHÔNG AI SDK)**, zxing (QR setup key).
- Onyx maven: `http://repo.boox.com/repository/maven-public/` (`settings.gradle.kts:15-17`).
- ABI release: `armeabi-v7a` + `arm64-v8a` (`app/build.gradle.kts:58-60`).

## 2. Kiến trúc tổng

- **God-activity**: 1 activity duy nhất wire ~9 controller (`ui/MainActivity.kt:84-236`) — anti-pattern
  cần tránh khi port. Không navigation lib/fragment; "màn hình" = panel/overlay.
- Package map (gốc `co.podzim.inka`): `brain/` LLM transport + conversation engine; `data/` models +
  NotebookStore + Prefs; `device/` nhận diện BOOX; `handwriting/` client synthesis server; `ink/`
  **InkCaptureController** (bút Onyx); `page/` **EinkRefresher** + renderer + overlay; `recognize/`
  ML Kit; `ui/` activity + controllers.
- **Màn hình chính = SurfaceView + raw pen layer của Onyx; reply đè bằng ReplyOverlayView.**

## 3. Cách gọi LLM (transport tự viết, không SDK)

- `OkHttpAnthropicTransport` → `api.anthropic.com/v1/messages` (SSE); `OkHttpOpenAiCompatibleTransport`
  → OpenAI/Groq `/v1/chat/completions` (`brain/ConversationEngine.kt:419-429`). 3 provider: Anthropic
  (default `claude-sonnet-4-6`), OpenAI, Groq.
- API key: `EncryptedSharedPreferences` (MasterKey AES256_GCM) file `"inka_secure"` (`data/Prefs.kt:20-31`).
- **Streaming có**; text qua `text_delta`, drawing qua tool `input_json_delta` + `StreamingDrawToolParser`
  (`ConversationEngine.kt:166-215`). maxTokens: text 300, drawing 2000.
- Suspend + `Dispatchers.IO`; mỗi delta text render Main + `delay(24-55ms)` = hiệu ứng gõ (`MainCommitController.kt:435-451`).
- **Vision có**: rasterize trang → grayscale → PNG base64 → image block (`PageSnapshotRenderer.kt:17-55`,
  `ConversationEngine.kt:313-321`). Model trả **tool `draw` với SVG `d` path (chỉ M L C Q Z)** →
  `SvgPathAdapter` → InkStroke render live (`ConversationEngine.kt:447-482`). Text turn chỉ gửi
  recognizedText (ML Kit), không kèm ảnh.
- History rebuild từ notebook JSON mỗi request, cắt 20 turn (`NotebookModels.kt:94-111`).

## 4. BOOX/E-Ink integration — quan trọng nhất cho RiddleBoox

- **Pen input**: `InkCaptureController` dùng `com.onyx.android.sdk.pen.TouchHelper` + `RawInputCallback`,
  `openRawDrawing()` — vẽ trên raw pen layer của Onyx cho latency thấp nhất (`ink/InkCaptureController.kt:15-18,161-173`).
  `STROKE_STYLE_FOUNTAIN`, `setPenUpRefreshEnabled(true)` (`:346-351`).
- **E-Ink refresh**: `EinkRefresher` dùng `com.onyx.android.sdk.api.device.epd.EpdController` với
  **`UpdateMode.HAND_WRITING_REPAINT_MODE`**, `refreshScreen(GC)`, `refreshScreen(DEEP_GC)`,
  `invalidate(..., DU)` (`page/EinkRefresher.kt:5-54`).
- **Cô lập hardware**: Onyx SDK sau đúng 2 class (`InkCaptureController` + `EinkRefresher`); fallback
  MotionEvent thuần cho emulator (`InkCaptureController.kt:170-173,276-336`). RiddleBoox nên giữ pattern này.
- Pen/finger phân biệt bằng guard; mỗi stroke lưu `onyxTouchPointList` base64 để replay bằng Onyx
  (`OnyxInkReplayRenderer.kt`).
- `DeviceCompatibility.isBooxDevice()` scan build fields "boox"/"onyx" (`device/DeviceCompatibility.kt:26-37`).

## 5. Handwriting & reply

- Input bút là chính, không có text keyboard. Điểm mang `x, y, pressure, size, tiltX, tiltY, timestamp`
  (`data/InkModels.kt:10-19`).
- Recognition: ML Kit Digital Ink on-device theo ngôn ngữ (`recognize/RecognitionService.kt:41-110`).
- Reply 3 kiểu: (1) font Dancing Script bundled, gõ từng ký tự có animation (`MainCommitController.kt:574-589`);
  (2) **handwriting synthesis server** tùy chọn trả stroke thật (PyTorch toolkit qua Docker, README.md:165-207);
  (3) drawing reply SVG path → stroke.
- **Stroke là dữ liệu gốc** — lưu JSON, không flatten ảnh (`NotebookModels.kt:43-46`).

## 6. Lưu trữ

- **JSON file** `filesDir/notebooks/{id}.json` pretty-print, atomic write (tmp → rename), `.damaged`
  recovery (`data/NotebookStore.kt:23-62`). Không Room.
- Schema v3: `Notebook { exchanges: [Exchange] }`, `Exchange { ink: NotebookInk?, reply: NotebookReply? }`
  (`NotebookModels.kt:6-60`). Write policy: sau recognition + trước LLM; lại khi có reply; `onStop` dự phòng.

## 7. Hệ quả cho RiddleBoox

1. **Mượn**: pattern cô lập Onyx SDK sau 2 class + fallback MotionEvent; cấu hình maven `repo.boox.com`;
   transport LLM không SDK (OkHttp + kotlinx-serialization, 2 endpoint SSE); vision gửi PNG grayscale;
   JSON file lưu stroke.
2. **API mới hơn doc chính thức**: Inka dùng `onyxsdk-pen:1.5.4` + `EpdController.refreshScreen` +
   `HAND_WRITING_REPAINT_MODE` — các API này KHÔNG có trong doc repo OnyxAndroidDemo (xem
   `onyx-sdk-api-reference.md` §8) → Inka là bằng chứng runtime tốt nhất.
3. **Tránh**: god-activity 633 dòng, 38 file `ui/`, lab activities; ML Kit nặng (tải model từng ngôn ngữ).
4. **Riddle khác Inka**: Riddle reply là nét viết tay theo nét bút (không font gõ, không synthesis
   server bắt buộc) — port trực tiếp từ Riddle `script.rs`; Inka không có hiệu ứng "mực biến mất".

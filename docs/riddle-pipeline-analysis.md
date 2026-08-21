# Riddle — phân tích pipeline (tham chiếu cho port BOOX)

> Nghiên cứu 2026-08-19, đọc trực tiếp `references/Riddle/src/*.rs`. Mọi citation dạng
> `file:line`. Confidence: HIGH = đọc trực tiếp code.

## 1. Persona prompt & LLM call (`src/oracle.rs`)

- System prompt hằng `PERSONA` tại `oracle.rs:27`: persona **"the memory of Tom Marvolo Riddle"**,
  viết reply ngắn 1-3 câu, thân mật/tò mò, cấm nhắc AI/images/photos, nếu không đọc được chữ nói
  "ink blurred", **trả lời bằng ngôn ngữ người viết**. (`oracle.rs:27`)
- Memory protocol (`MEMORY_PROTOCOL`, `oracle.rs:31`): catalog trang đã nhớ, `⟦show:N⟧` để xem lại,
  bắt buộc kết thúc reply bằng `⁂` + transcription nguyên văn trang hiện tại.
- Giao thức: **OpenAI-compatible chat-completions**, `stream:true`, user message
  `content:[{type:text},{type:image_url,image_url:{url:"data:image/png;base64,..."}}]` — `oracle.rs:479-505`.
  Model mặc định `gpt-4o-mini` (`oracle.rs:411`), base URL `https://api.openai.com/v1` (`oracle.rs:407`),
  override bằng env → chạy được OpenAI/OpenRouter/Groq/local.
- Client `HttpOracle` (`oracle.rs:392-569`): body JSON tự build tay (không thư viện), thread riêng,
  `ureq` TLS rustls, read timeout 90s, retry 1 lần (HTTP 400 `max_completion_tokens` → đổi field).
- **Streaming + viết sớm**: reply chunk theo câu (`sentence_cut`, `oracle.rs:614-626`), quill bắt đầu
  viết trước khi model xong (`oracle.rs:535-565`). Backend thứ hai `PiOracle` (process `pi --mode rpc`)
  khi không có API key (`oracle.rs:183-191`).

## 2. Pipeline nét bút → PNG

- Input evdev thô (`pen.rs:101-166`): `PenSample` mỗi SYN_REPORT, x/y map screen coords
  (`pen.rs:152-153`), **pressure 0-4096** (`pen.rs:16`), `touching`, `proximity`, tool Pen/Eraser.
  Vẽ khi `touching && pressure > 40` (`main.rs:327`), radius `r = 2 + pressure*3/4096` (`main.rs:345`).
- Ink model (`ink.rs`): `pen_point` stamp/brush_line màu BLACK lên Surface + push `(x,y,r)` vào stroke
  (`ink.rs:38-50`); pen-up chuyển sang `strokes` (`ink.rs:100-105`). **Eraser** vẽ trắng + split stroke
  (`ink.rs:56-98`).
- **Rasterize PNG** (`ink.rs:111-147`): crop bbox + 20px margin, **downscale cạnh dài ≤ 800px** (tối
  thiểu 2x), grayscale 8-bit, PNG fast. Trang full 1620×2160 → ~540×720.
- Vẽ không anti-aliasing (stamp hard-edge, `surface.rs:181-200`); mượt khi downscale nhờ box-average.
- PNG → `/tmp/riddle-page.png`, xoá sau khi ask (`main.rs:460-462`).

## 3. "Mực biến mất" khi nhấc bút

- Single-thread loop (`main.rs:263-749`, sleep 2ms). State machine: `Listening → Drinking → Thinking
  → Replying → Lingering → FadingReply` (`main.rs:413-745`).
- Idle: pen-up set `last_pen`; sau **2800ms** (`IDLE_COMMIT`, `main.rs:37`) không có nét mới → commit
  (`main.rs:415`). Trước khi gọi LLM: check trang trắng → clear; dấu `?` → mở guide; không có oracle →
  viết excuse (`main.rs:416-437`).
- **Mực biến mất**: `Drinking` — `dissolve_pass` (`ink.rs:161-172`) xoá pixel theo hash, **14 stages ×
  70ms** (`main.rs:471,480`), xong clear → Thinking.
- Loading: chấm đen giữa màn, pulse 600ms (`main.rs:535-543`), timeout 120s.

## 4. Reply tự viết

- **Font chữ viết tay DancingScript.ttf** (`main.rs:34`), không phải bézier paths. Pipeline
  (`script.rs`): `rasterize_line` (text→bitmask, threshold cov>0.5) → `thin` (skeleton Zhang-Suen 1px,
  `script.rs:68-124`) → `trace` (polylines, stroke trái→phải, `script.rs:128-196`) → `wrap` (word-wrap,
  `script.rs:199-217`).
- Layout (`plan_reply`, `main.rs:861-892`): font 96px, line height 120, căn giữa, **jitter ±3px mỗi dòng**.
- Animation: mỗi tick 14ms vẽ ≤26 điểm (`main.rs:591-632`) → ~1857 điểm/s, radius 2.
- **Fade sau khi viết xong**: `Lingering` giữ 4s + 2ms/điểm, cap 20s (`main.rs:628-645`) → `FadingReply`
  10 stages × 80ms + **full_refresh chống ghost** cuối (`main.rs:729-744`).

## 5. Ink user vs ink reply — khác biệt then chốt

| | User ink | Reply ink |
|---|---|---|
| Nguồn | điểm evdev, có pressure | pixel font → skeleton → polylines, không pressure |
| Lưu | `(x,y,r)` | `(x,y)` |
| Sinh | realtime trong loop | pre-plan `WritePlan` → phát lại theo tick |
| Radius | 2-5 theo pressure | cố định 2 |
| Fade | 14×70ms | 10×80ms + full refresh |

Conjure (replay memory) là đường thứ ba: vẽ lại stroke user với màu `FADED` 0x7BCF (`main.rs:682-688`).

## 6. Hệ quả cho port Android

1. **Port 1:1 được**: persona prompt + protocol `⟦show:N⟧`/`⁂`, StreamParser, HttpOracle (→ OkHttp + SSE),
   pipeline reply viết tay (script.rs), state machine — toàn bộ thuần logic, không đụng hardware.
2. **Làm khác — input**: evdev grab (`pen.rs:73`) → `TouchHelper`/`MotionEvent` của BOOX; giữ công thức
   radius `2 + pressure*3/4096`.
3. **Làm khác — hiển thị**: quill/qtfb → Onyx SDK refresh (xem `onyx-sdk-api-reference.md`).
4. **Timing cần test lại trên E-Ink**: dissolve 70-80ms/stage, tick 14ms — độ trễ waveform BOOX khác
   reMarkable (xem `refresh-strategy.md`).

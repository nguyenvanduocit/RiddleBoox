# RiddleBoox

Port ý tưởng **Riddle** (nhật ký AI viết tay của Tom Riddle — reMarkable Paper Pro) sang
máy **BOOX (Onyx)**, kết hợp cách làm native Android của **Inka**.

> Viết bằng bút trên trang giấy, nhấc bút một lúc — mực "biến mất", trang suy nghĩ, rồi câu trả lời
> tự viết lại bằng nét bút rồi mờ dần. Không bàn phím, không chat UI. Chỉ mực trên giấy.

## Ý tưởng

- **Riddle** (https://github.com/MaximeRivest/Riddle) — Rust, dùng `quill` driver đè lên engine
  waveform của reMarkable để có nét mực tức thì; LLM vision đọc PNG trang viết tay và trả lời.
- **Inka** (https://github.com/BoltAI/Inka) — app native Android AI journal chạy trên BOOX
  (cài qua Play/sideload, dùng EMR stylus). Là mẫu tham chiếu trực tiếp cho việc app AI chạy trên BOOX.

## Agents trong RiddleBoox

RiddleBoox có khái niệm **agent riêng của ứng dụng**, không liên quan đến agent mặc định
của Claude Code. Mỗi agent có tên, mô tả, system prompt và workspace riêng trên disk:

```
<filesDir>/agents/<agent-id>/
├── agent.json       # tên, tools và bộ greeting riêng của agent
├── system.md       # prompt được nạp làm system message
└── workspace/      # artifact và memory riêng của agent
```

Lần chạy đầu app tạo năm agent mặc định: `chat`, `library`, `notes`, `english-tutor` và
`agent-manager`. Chạm tên agent trên dòng đầu của trang để chọn, tạo, sửa hoặc xóa agent
tùy chỉnh. Built-in có prompt, identity và toàn bộ capability được app quản lý theo phiên bản;
chúng không thể sửa hay xóa. Muốn tùy biến một built-in, hãy nhân bản nó thành agent riêng.

Trong lúc diary đang gọi model hoặc đang viết câu trả lời ra giấy, cạnh dòng trạng thái ở đầu
trang hiện nhãn **dừng** (chỉ hiện khi đang bận). Chạm vào nó:

- **Chưa viết được chữ nào** (đang uống mực / đang chờ model) — bỏ request, trả trang trắng.
  Trang chữ vừa gửi đi không quay lại: nó đã được giao đi từ lúc bị lấy.
- **Đang viết dở** — bút dừng ngay tại nét đang chạy, phần chữ đã hiện ở lại làm câu trả lời
  đang đứng, và nhật ký được ghi lại đúng bằng phần đó. Điều này cần một bước sửa: một câu trả
  lời streaming được ghi vào nhật ký ngay khi stream đóng, tức nhiều giây trước khi bút viết
  xong nó — nên khi dừng tay, `ConversationStore.replaceLastReply` viết đè lượt vừa ghi bằng
  đúng những gì người viết đã nhìn thấy (`ReplySoFar` giữ mốc chữ ↔ số nét để cắt). Không có
  bước này thì diary sẽ "nhớ" cả đoạn mà người viết chưa từng đọc.

Mỗi agent có bộ greeting riêng được lưu trong `agent.json`. Khi tạo hoặc sửa agent, nhập mỗi
câu trên một dòng trong trường `greetings`; khi mở trang mới, app chọn ngẫu nhiên từ đúng bộ
của agent đang được chọn và tránh lặp lại câu vừa dùng.

Mọi agent đều mang sẵn **bộ tool mặc định** — workspace và memory — được inject vô điều kiện
trong `MainActivity.agentToolbox`, không cần khai trong `agent.json` và không tắt được. Built-in
còn có toàn bộ capability `library`, `dilib` và `boox_notes`; riêng `agent-manager` có thêm
`agent_management`. Agent custom chỉ có những capability người dùng chọn trong màn hình Edit.
Model chỉ nhận descriptor của capability được cấp nên không biết những tool ngoài vai trò của
mình tồn tại.

**Vẽ hình không phải tool** mà là giao thức in-band như `[[circle]]` và LaTeX: model đặt thẳng
`<svg>…</svg>` vào reply tại đúng chỗ hình thuộc về (TURN_PROTOCOL trong `reply/Conversation.kt`),
nên mọi agent và **mọi model** — kể cả model không hỗ trợ tool-call — đều vẽ được, không tốn
thêm request nào. Stream giữ block chưa đóng lại như giữ mark chưa đóng (`reply/ReplyCut.kt`:
`writableCut`/`completedSvgBlock`), block đóng xong thì `handwriting/SvgInk.kt` parse + flatten
thành polyline thuần Kotlin (đủ grammar `path` kể cả arc, shapes, transforms) và
`WriteCursor.placeFigure` tự chọn vị trí (dưới dòng đang viết), tự scale (tối đa ~1/3 trang,
căn giữa, luôn chừa một dòng chữ bên dưới), decimate điểm theo tốc độ reveal — hình hiện ra
từng nét như chữ viết tay. Markup bị strip khỏi mọi chỗ giữ chữ (`stripSvgBlocks`: bản ghi
lượt, history mang sang lượt sau) nên không bao giờ thành mực góc-nhọn hay token phí; trang
gần hết chỗ hoặc markup hỏng thì hình bị bỏ qua với log warning. Hình không đi vào transcript
nên resume một cuộc trò chuyện cũ chỉ vẽ lại phần chữ.

Workspace tools an toàn gồm list, read, write, append, edit, delete, mkdir, stat, filename
search, grep và regex search. Path traversal, absolute path, symlink thoát khỏi workspace và
thao tác quá lớn đều bị chặn. Capability của agent custom chỉnh trong màn hình Edit hoặc nhờ
`agent-manager`; built-in luôn giữ nguyên toàn bộ capability và `agent_management` vẫn chỉ thuộc
về built-in manager.

BOOX có lợi thế: chạy Android → app là Android Activity bình thường, không cần đụng vendor engine
(đã có sẵn `onyxsdk-pen`/`EpdController` cho stylus + refresh E-Ink).

## Cấu trúc

```
RiddleBoox/
├── README.md
├── docs/
│   ├── boox-app-development-research.md   # Deep research nền tảng BOOX + Onyx SDK
│   ├── riddle-pipeline-analysis.md        # Riddle: persona prompt, nét bút → PNG → LLM → reply tự viết
│   ├── inka-architecture-analysis.md      # Inka: kiến trúc Android, LLM, Onyx SDK isolation
│   ├── onyx-sdk-api-reference.md          # API TouchHelper/EpdController đã xác minh + sửa lỗi research cũ
│   ├── refresh-strategy.md                # Map state Riddle → refresh mode BOOX
│   ├── release-signing-runbook.md         # Keystore ở đâu, cách ký AAB, backup trước khi mất key
│   └── crash-reporting.md                 # Setup Firebase Crashlytics (conditional trên google-services.json)
└── references/
    ├── Riddle/             # repo gốc (Rust, reMarkable) — đã clone
    ├── Inka/               # app native Android AI journal cho BOOX — đã clone
    └── OnyxAndroidDemo/    # Onyx SDK chính thức + doc — đã clone
```

## Landing page

Landing page public của project nằm trong `pages/` và được deploy dạng static lên Cloudflare Pages:

- Production: `https://riddleboox.pages.dev`
- Custom domain: `https://riddleboox.aiocean.io`
- Deploy lại: `./scripts/deploy-landing.sh`
- Quy trình, DNS, auth và troubleshooting: [`docs/landing-page-deployment.md`](docs/landing-page-deployment.md)

Landing page không dùng build tool; Cloudflare nhận nguyên thư mục `pages/`. Các page state trong
section dùng `DancingScript.ttf` của app để chữ ví dụ luôn rõ và có cùng giọng điệu; các ảnh
`page-*.png` chỉ là sample nét viết thô, không phải dữ liệu runtime của Android app.

Landing page có Android download CTA và Android robot icon ở header, khu vực download và CTA
cuối trang. Mọi nút dùng chung một đích: `androidDownloadUrl` ở đầu `pages/script.js`, trỏ tới
listing Google Play (`https://play.google.com/store/apps/details?id=com.riddleboox.app`).

Thông điệp cốt lõi của landing page: RiddleBoox không chỉ là một ô chat trên BOOX. Đây là một
người bạn AI lớn lên cùng những gì người dùng viết — có thể là người thầy, người đồng hành, người
cùng đọc sách hoặc trợ lý làm việc. Viết được đặt trước prompt vì viết tay không chỉ nhập chữ;
nó làm chậm sự chú ý, giúp suy nghĩ rõ hơn và cho AI nhiều ngữ cảnh hơn về con người đang viết.

Section Agent support trên landing page minh họa bốn use case: book agent để tìm/tải sách, English
tutor, reflection companion để tự soi chiếu (không thay thế chuyên gia tâm lý), và sharing companion
để có một nơi chia sẻ và quay lại những trang cũ. Đây là ví dụ về agent có thể tạo, không phải bốn
built-in agent cố định; agent thật có prompt, greeting, workspace và capability riêng.

Mạch hình ảnh chính của landing page là hai lượt tâm sự ngắn: người dùng viết “Today, something
feels heavy.”, được lắng nghe và nhận lời vỗ về, sau đó có một khoảng nghỉ rõ ràng trước khi viết
tiếp “I still feel it, but I can name it now.”. Bút chỉ xuất hiện trong lượt người dùng viết;
AI trả lời bằng chữ hiện lại trên trang. Chi tiết stage của animation nằm trong `pages/script.js`;
khi đổi thông điệp, cập nhật đồng thời `stages` và các page mockup trong `pages/index.html`.

## Thiết lập máy BOOX (bắt buộc, 1 lần)

Onyx SDK (`onyxsdk-pen`) reflect vào hidden API (`Device.getBoardPlatform()`) để mở
kênh digitizer thật. Trên chính sách hidden-API mặc định của máy, ART chặn các
reflection đó — kể cả `VMRuntime.setHiddenApiExemptions`, nên không một thư viện bypass
in-app nào mở được (Google Play cũng từ chối bản build chứa API bypass SDK từ đợt
targetSdk 36). Hệ quả nếu thiếu setting: `TouchHelper.openRawDrawing()` "thành công"
giả nhưng `onBeginRawDrawing` không bao giờ fire, tức viết bút không ra mực. Trước khi
cài/chạy app lần đầu trên một máy BOOX mới, chạy một lần:

```
adb shell settings put global hidden_api_policy 1
```

Setting này lưu ở `Settings.Global`, sống qua reboot (chỉ mất khi factory reset máy).


## Nhật ký tra được gì

Nhật ký chạy trên một máy đọc sách, nên nó nhìn thấy chính cái máy đó. Model được cấp các
tool theo agent, tự quyết định khi nào cần
dùng, và `PERSONA` cấm nó nhắc tới việc tra cứu — thứ tìm được phải được nói ra như thể nó
vốn đã biết.

Capability `boox_notes` đọc đúng các notebook được tạo trong app **Notebook của BOOX**, không
phải note trong workspace của RiddleBoox và cũng không phải highlight của NeoReader:

| Tool | Trả về |
| --- | --- |
| `list_boox_notes` | Danh sách notebook BOOX, số trang, thời gian cập nhật và favorite |
| `search_boox_notes` | Tìm notebook theo tên |
| `open_note` | Mở ứng dụng BOOX Notebook; có thể nêu tên note để xác định note cần mở |
| `read_boox_note` | Đọc một trang theo tên hoặc ID; lấy typed text từ dữ liệu BOOX và dùng vision model để đọc ảnh export nếu có |
| `create_boox_note` | Mở một notebook rỗng có sẵn một trang trắng để người dùng viết tay sau |
| `rename_boox_note` | Đổi tên notebook, không đụng tới trang |
| `delete_boox_note` | **Xóa hẳn** notebook: row trong provider, dữ liệu nét viết trong `.ksync`, và ảnh export trong `/sdcard/note` |

Notebook provider là `com.onyx.android.sdk.note.ContentProvider/NoteModel`. BOOX lưu nét viết tay
gốc trong dữ liệu `.ksync` riêng; nếu trang chưa có ảnh export hoặc OCR/typed text thì tool sẽ
nói rõ là chưa có nội dung chữ để agent không tự đoán.

| Tool | Trả về |
| --- | --- |
| `search_library` | Sách trên máy, tiến độ đọc, lần mở cuối, số đoạn đã đánh dấu. Không có query → cuốn đang đọc dở |
| `open_reader` | Mở một cuốn sách đã chọn trong NeoReader |
| `book_contents` | Mục lục đánh số của một cuốn + trang người đọc đang đứng |
| `read_book` | Chữ của một chương (EPUB), cắt ở 5000 ký tự và nói rõ offset để đọc tiếp |
| `search_in_book` | Tìm một cụm từ trong cả cuốn, trả về đoạn văn quanh chỗ khớp |
| `read_highlights` | Đoạn người đọc bôi vàng + ghi chú bên lề. Chạy cả với PDF |
| `recall_diary` | Những buổi tối cũ trong nhật ký, tìm theo chữ đã viết hoặc đã trả lời. In kèm id 8 ký tự để gọi tên lại |
| `delete_book` | **Xóa hẳn** một cuốn: file trên máy, entry trong thư viện, và mọi đoạn đã đánh dấu trong nó. `keep_file=true` thì chỉ rút khỏi thư viện |
| `delete_highlight` | **Xóa hẳn** một đoạn đã đánh dấu, gọi theo id mà `read_highlights` in ra |
| `forget_diary` | **Đốt hẳn** một buổi tối trong nhật ký, theo id hoặc theo chữ đã viết trong buổi đó |

Khi agent `library` có capability `dilib`, nó còn có một kệ sách tiếng Việt online
(dilib.vn), không cần tài khoản:

| Tool | Trả về |
| --- | --- |
| `search_dilib_books` | Kết quả từ dilib.vn, gồm id số, tiêu đề, tác giả, số trang và lượt tải |
| `download_dilib_book` | Tải sách đã chọn vào `/sdcard/Books`; mặc định lấy EPUB nếu có, `format` để chọn PDF |

Tải xong thì kích hoạt media scan để NeoReader nhận lại thư viện, và agent chỉ tải sau khi
người dùng chọn hoặc xác nhận đúng id. Vài chi tiết của dilib không nhìn từ trang web mà ra:
kết quả tìm kiếm không nằm trong trang search mà do jQuery lấy từ `/search/ajax-search.php`,
id số là địa chỉ đầy đủ (`/403.html` tự chuyển về đúng slug), và link tải trỏ ra Google Drive —
Drive trả trang cảnh báo thay vì file khi sách lớn, nên `DilibClient` tự thử lại với `confirm=t`.
Thẻ tìm kiếm chỉ in một định dạng kể cả khi sách còn bản EPUB, nên định dạng thật chỉ được
chốt ở trang sách lúc tải.

Ba tool cuối và `delete_boox_note` không có thùng rác và không hoàn tác được. Hai chỗ chặn
tay lỡ: gọi tool mà không nêu tên sách/note thì **không** xóa đại cuốn đầu tiên, và một
mẩu id hay một cụm từ khớp nhiều thứ thì liệt kê ra chứ không đoán. Prompt (`Conversation.kt`)
cũng dặn chỉ xóa khi người viết yêu cầu đúng thứ đó, và phải nói lại đã xóa gì.

Hai nguồn dữ liệu, hai điều kiện khác nhau:

- **Thư viện, tiến độ, ghi chú** đến từ content provider mà NeoReader tự publish —
  authority `com.onyx.content.database.ContentProvider`, bảng `Metadata` / `Annotation` /
  `Bookmark`, exported và không đòi permission. `Annotation.idString` chính là
  `Metadata.uuid`, đó là khoá nối ghi chú với sách. Provider này nhận cả `delete()` từ
  app khác và tôn trọng `where`, nên xóa sách/ghi chú là xóa thật trong DB của NeoReader
  chứ không chỉ unlink file — unlink một mình sẽ để lại cuốn sách mở ra không có gì. Chỉ cần khai `<queries><provider>`
  trong manifest, nếu không package visibility (API 30+) sẽ khiến `query()` trả về `null`
  — trông y hệt một máy không có cuốn sách nào.
- **Chữ trong sách** đọc thẳng từ file `.epub` (`library/Epub.kt`: zip → `container.xml` →
  OPF spine → NCX/nav → strip thẻ). Cái này cần quyền **all-files access**, vì từ Android 10
  `READ_EXTERNAL_STORAGE` chỉ mở media và một file `.epub` không phải media. Bật nó ở dòng
  **"sách trên máy"** trong màn hình cấu hình của app: dòng đó nói đang đọc được tới đâu, chạm
  vào là mở thẳng công tắc của Android (`library/BookAccess.kt`). Chưa bật thì nhan đề, tiến độ
  và ghi chú vẫn chạy đủ — chỉ chữ bên trong sách là chưa mở được.

  **PDF không đọc được nội dung** — chỉ metadata và ghi chú; muốn đọc chữ trong PDF phải kéo
  thêm một thư viện trích text, hiện chưa làm.

Ba điểm thiết kế đáng nhớ:

- **Model phải khai `LLMCapability.Tools`**, nếu không koog **âm thầm bỏ** field `tools`
  khỏi request và nhật ký sẽ không bao giờ tra gì — trông như model bướng chứ không như
  request thiếu (cùng một cái bẫy với `reasoning_effort` / `LLMCapability.Thinking`).
- **Tối đa 6 vòng tra cứu một lượt** (`MAX_LOOKUPS`). System protocol nói rõ ngân sách và
  yêu cầu model batch các call độc lập trong cùng một vòng. Ở vòng cuối model nhận cảnh báo
  phải nói rõ phần chưa làm; sau đó tool không được đưa vào request nữa nên lượt luôn dừng.
- **Kết quả tra cứu không được nhớ.** Một lượt xong thì nhật ký chỉ giữ lại chữ trên trang
  và câu trả lời của chính nó, y như khi không có tool. Một chương sách đọc để trả lời một
  câu mà nằm lại trong history thì mọi lượt sau của buổi tối đều phải trả tiền cho nó.

## Ba cái bẫy khi verify qua adb

- **`am start` báo `Activity class ... does not exist` dù `pm list packages` thấy app**: máy đang để
  app ở trạng thái vô hiệu hoá. `pm`, `am` và `dumpsys` là lệnh của Android, không có trên máy tính
  — mọi lệnh dưới đây đều phải đi qua `adb shell`:

  ```
  adb shell dumpsys package com.riddleboox.app | grep enabled   # enabled=3 = DISABLED_USER
  adb shell pm enable com.riddleboox.app                         # bật lại
  ```

  Cài đè APK không gỡ được trạng thái này.
- **`adb exec-out screencap -p` ra file PNG hỏng**: firmware BOOX in một dòng
  `capture from screenshot!` trước dữ liệu ảnh. Cắt từ byte đầu của header `\x89PNG` trở đi thì
  ảnh đọc được bình thường.
- **`am broadcast --es text "..."`**: chuỗi bị shell trên máy tách lại theo dấu cách, nên câu nhiều
  chữ biến thành `pkg=<chữ thứ hai>`. Quote hai lớp: `--es text "'Ten toi la Duoc'"`.

## Việc cần làm

- [x] Chọn model BOOX mục tiêu → **Note Air 2** (serial 9078B274, máy của user): Android 11 (SDK 30),
      security patch 2024-02-01, WebView 150.0.7871.183, có Play Store (`com.android.vending`),
      Inka 0.1.4 chạy rất tốt (minSdk 29/target 35 khớp máy)
- [x] Đọc `references/Riddle` kỹ → `docs/riddle-pipeline-analysis.md`
- [x] Đọc `references/Inka` kỹ → `docs/inka-architecture-analysis.md`
- [x] Đọc `onyx-intl/OnyxAndroidDemo` doc/ → `docs/onyx-sdk-api-reference.md`
- [x] Thiết kế refresh strategy → `docs/refresh-strategy.md` (đang vẽ = raw drawing, reply = HAND_WRITING_REPAINT/DU, cuối = GC; cần verify trên máy thật)
- [ ] Scaffold Android app + Onyx SDK (lấy pattern cô lập 2 class của Inka)

## Mã hoá lưu trữ, CI, xuất hội thoại, và nền PIN-lock

- **Hội thoại mã hoá tại rest**: `ConversationStore` (`history/ConversationStore.kt`) mã hoá JSON
  qua interface `ConversationCipher` trước khi ghi đĩa và giải mã trước khi đọc. App thật dùng
  `KeystoreConversationCipher` (`history/KeystoreConversationCipher.kt`): AES-256-GCM, key sinh
  và ở lại trong Android Keystore, IV ngẫu nhiên cho mỗi lần mã hoá rồi prepend vào ciphertext để
  `decrypt` lấy lại. Test JVM dùng `NoOpConversationCipher` (mặc định của constructor 2 tham số)
  vì Keystore không tồn tại ngoài Android runtime.
- **CI chạy unit test**: `.github/workflows/test.yml`, trigger trên `push`/`pull_request` nhánh
  `main`, chạy `./gradlew testDebugUnitTest --no-daemon`; fail thì upload report từ
  `app/build/reports/tests/testDebugUnitTest/`.
- **Xuất hội thoại ra text để chia sẻ**: nút "chia sẻ" trên `TranscriptActivity` (hàm `share()`,
  `history/TranscriptActivity.kt`) ghi `StoredConversation.toPlainText()`
  (`history/ConversationText.kt`) ra `cacheDir/exports/<id>.txt`, rồi mở share sheet Android qua
  `FileProvider` (authority `${applicationId}.onyx.fileprovider`, path map ở
  `res/xml/file_paths.xml`).
- **Nền PIN-lock — chưa hoàn chỉnh**: `PinStore`/`PinHash` (`settings/PinStore.kt`,
  `settings/PinHash.kt`) lưu PIN dạng salted SHA-256 hash trong `EncryptedSharedPreferences`
  (Keystore-backed), có toggle đặt/xoá trong màn hình Settings (`SettingsActivity.kt`, hàm
  `togglePin`, nhập PIN mới phải gõ lại lần 2 khớp mới lưu). `LockActivity`
  (`settings/LockActivity.kt`) là màn hình nhập PIN hoàn chỉnh, gọi `PinStore.verify()`, nhưng
  đứng độc lập — **không Activity nào khởi chạy nó**. Chưa có gì gate `MainActivity`: quyết định
  "khi nào phải nhập lại PIN" (chỉ cold-start? mỗi lần quay lại từ background? có timeout?) là
  quyết định sản phẩm, để dành cho một chu kỳ có review trực tiếp.

## Tính năng thêm sáng 2026-08-22 (sau vòng mã hoá/CI/export/PIN ở trên)

`HistoryActivity` và `MemoriesActivity` giờ đối xứng nhau về tính năng — cả hai đều có ô tìm
kiếm (nút "xoá" riêng, chỉ hiện khi có chữ) và nút "xuất tất cả":

- **Xuất toàn bộ**: `List<StoredConversation>.toPlainText()` (`history/ConversationText.kt`)
  và `List<MemoryEntry>.toPlainText()` (`tools/MemoryText.kt`) — 2 hàm độc lập, mỗi mục có
  tiêu đề ngày riêng, không sort lại (giữ nguyên thứ tự caller đưa vào).
- **Tìm kiếm khoan dung dấu tiếng Việt**: `history/HistorySearch.kt`, `tools/MemorySearch.kt`,
  cùng dùng `fold()` (`library/Folded.kt`) mà `DiaryMemory.recall` đã dùng cho model, giờ có
  luôn cho writer.
- **Quên 1 memory / sao chép vào clipboard**: trực tiếp từ `MemoriesActivity` và
  `TranscriptActivity`, không cần quay lại trang nhờ agent gọi `forget_memory` hay mở share
  sheet chỉ để dán nhanh. `writeMemories()` (`tools/MemoryTools.kt`) dùng chung giữa tool và UI.

`AgentsActivity`:

- **Nhân bản** (mọi agent, kể cả built-in — `AgentStore.create()` luôn strip `agent_management`
  cho agent không builtin) và **xuất** (`agent/AgentText.kt`, chỉ cấu hình — tên/mô tả/tools/
  greetings/system prompt — không đụng workspace của agent).
- **Xem thử greeting** ngay trong form, không cần lưu rồi mở trang mới mới thấy.
- Trường "id" bị khoá khi sửa agent đã tồn tại (trước đó gõ được nhưng bị âm thầm bỏ qua khi lưu).

`SettingsActivity`:

- **Cỡ chữ đọc lại độc lập với cỡ chữ trả lời**: `TranscriptFontSize`/`TranscriptFontSizeStore`
  (`settings/TranscriptFontSize.kt`) — khác `ReplyFontSize` (đơn vị px cho nét chữ viết tay
  rasterize), đây là sp cho `TextView` thường khi đọc lại.
- **Base url chọn theo tên** (OpenAI / OpenRouter / other — chọn "other" mới hiện ô gõ url,
  xem `settings/Providers.kt`), **api key che dạng password**, **"set up from phone"** mở
  `PairActivity` — QR trỏ vào một HTTP server nhúng (`settings/PairingServer.kt`, ktor-server-cio)
  trên LAN của máy, điện thoại quét, điền base url/api key/model trên một trang HTML tự chứa
  (`settings/PairingPage.kt`) rồi gửi về; server chỉ sống khi màn hình ghép nối đang mở, mỗi
  token dùng đúng một lần, **danh sách model hỏi thẳng
  server** (`GET /v1/models`, lọc model đọc được ảnh — `reply/ModelCatalog.kt`; offline rơi về
  shortlist đo tay trong `VISION_MODELS`), **nhập tay model id**, **khôi phục mặc định** cho
  base url + model nằm ngay trên tiêu đề mục "connection" (cố ý loại api key — xem
  `resetConnectionDefaults()`), **cảnh báo base url thừa `/v1`** trước khi lưu, **hiển thị
  phiên bản app**, **PIN mới phải gõ lại lần 2 khớp mới lưu**.

## Quyết định thiết kế

- **Không dùng handwriting recognizer** (ML Kit của Inka quá dở) — giữ pipeline của Riddle:
  gửi thẳng PNG trang viết tay cho **vision LLM** đọc (`oracle.rs:479-505`), fallback "ink blurred".
- **Vision model configurable** (mặc định OpenAI-compatible, user đổi được trong settings).
- **PNG resolution**: để ngỏ cho model mạnh hơn (Riddle cố định 800px — cân nhắc nâng khi cần).
- **Oracle chạy qua koog** (`ai.koog:koog-agents` + `ai.koog:http-client-ktor`, JetBrains,
  Apache-2.0, Kotlin Multiplatform) — `askDiary` (`oracle/AskDiary.kt`) dùng `OpenAILLMClient`
  của koog, sẵn sàng cho tính năng agent tương lai (nhớ lại nhật ký cũ, tool-calling).
  Cần Kotlin 2.3.10+ / JDK 17+.
  **Base URL là gốc server, không kèm `/v1`** (`https://api.openai.com`): koog tự nối
  `v1/chat/completions`, nên thừa `/v1` sẽ POST vào `/v1/v1/chat/completions` và nhận 404
  rỗng.
  `OpenAILLMClient` nhận `httpClientFactory` tường minh (`KtorKoogHttpClient.Factory()`) thay vì
  dựa vào auto-discovery qua classpath — thiếu `http-client-ktor` sẽ crash ngay lúc khởi tạo với
  `IllegalStateException: No KoogHttpClient.Factory provider found`.
  Khác biệt hành vi cần biết: koog luôn gửi `max_completion_tokens` cho Chat Completions API,
  không có fallback `max_tokens` như bản cũ — server OpenAI-compatible chỉ hiểu `max_tokens` sẽ
  bị từ chối mọi turn.

## Quyết định cần user (trước khi scaffold)

- Verify 4 mục trong `docs/refresh-strategy.md` trên máy thật (HAND_WRITING_REPAINT_MODE, setGcInterval
  đơn vị, timing dissolve, setPenUpRefreshEnabled) — làm dần khi scaffold phần pen/refresh.

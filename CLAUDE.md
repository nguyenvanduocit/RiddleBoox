# RiddleBoox notes

- Khi cần xem request/response của API, dùng `ego-browser` để kiểm tra trực tiếp.
- Debug build (chỉ `BuildConfig.DEBUG`) expose adb broadcast `com.riddleboox.app.DEBUG_CONTROL` (`MainActivity.kt`) để điều khiển app từ xa khi màn hình e-ink khó đọc/tương tác trực tiếp:
  - `adb shell "am broadcast -a com.riddleboox.app.DEBUG_CONTROL --es cmd 'memorize'"` — bấm nút memorize.
  - `adb shell "am broadcast -a com.riddleboox.app.DEBUG_CONTROL --es cmd 'state'"` — đọc state hiện tại + lỗi cảnh báo gần nhất (`RiddleStateMachine.lastWarning`).
  - `adb shell "am broadcast -a com.riddleboox.app.DEBUG_CONTROL --es cmd 'position'"` — đọc vị trí Y nơi khối chữ debug tiếp theo sẽ bắt đầu.
  - `adb shell "am broadcast -a com.riddleboox.app.DEBUG_CONTROL --es cmd 'write nội dung'"` — vẽ chữ thật lên trang, nối tiếp dưới standing reply (đi qua reply pipeline giống AI, không phải `commitDemoText`/`DEMO_WRITE` — cái đó giả lập trang người dùng viết rồi hỏi AI, chữ sẽ bị "uống" đi chứ không đứng yên).
  - `am broadcast` gửi ordered broadcast nên kết quả (`setResultData`) in thẳng ra output của lệnh adb, không cần đọc logcat/chụp màn hình riêng.
  - Chỉ hoạt động khi Activity đang resumed và `state is Listening` (không có turn nào đang chạy, không có nét bút chưa gửi).

## Codebase là tài liệu sống

- Mỗi thay đổi hành vi, lifecycle hoặc kiến trúc đáng kể phải cập nhật tài liệu liên quan trong cùng thay đổi.
- Comment nên giải thích **vì sao** và invariant cần giữ, đặc biệt với lifecycle Android và refresh E-Ink.
- Khi phát hiện một bẫy hoặc quy tắc có thể lặp lại, ghi lại vào `CLAUDE.md`/`docs/` để các lần làm việc sau không phải suy luận lại.

## Window & insets (`ui/Paper.kt`)

- Mọi Activity mở window qua `openPaperWindow()`. Window là **edge-to-edge** (`WindowCompat.setDecorFitsSystemWindows(false)`), system bars ẩn qua `WindowInsetsControllerCompat` và được ẩn lại mỗi lần window lấy lại focus (dialog, bàn phím). Không dùng lại `View.systemUiVisibility`.
- Edge-to-edge nghĩa là framework **không** dịch hay co gì cho bar/bàn phím nữa — `adjustResize` một mình vô hiệu. Trang phải tự nhận insets: dòng chrome trên cùng dùng `holdUnderSystemBars(base)` (running head, chrome row của MainActivity), root của `paperPage` dùng `holdAboveKeyboard()`. Không view nào được giả định y = 0 là mép giấy.
- Mọi `EditText` gọi `keepPageVisible()` (`IME_FLAG_NO_FULLSCREEN | IME_FLAG_NO_EXTRACT_UI`). Vì sao: bàn phím có thể vào **full-screen (extract) mode** — thay cả trang bằng ô soạn thảo riêng + nút DONE, không còn running head, không "save", không status bar. Đây là triệu chứng ghi trên BOOX Go 7 / Go Color 7 / Go 10.3 sau khi gõ api key; tái hiện được trên emulator Android 13 stock với window dạng landscape (`docs/known-issues.md` mục 9). Không xử lý IME insets thì bàn phím chỉ **phủ** lên ô đang gõ (window không co, không pan) — `holdAboveKeyboard()` sửa phần đó. Test canh gác: `ui/PaperInsetsTest.kt`, `ui/KeyboardOnThePageTest.kt`, `settings/SettingsWidgetsTest.kt`.
- Màn có `EditText` khai `android:windowSoftInputMode="adjustResize"` trong manifest (Settings, Lock, Agents, History, Memories) — API 30+ dùng cờ này để quyết định có dispatch `Type.ime()` insets.

## Unit test (Robolectric)

- Test Robolectric nào gọi API Android ≥ 23 (ví dụ `Context.getSystemService(Class)`) phải pin `@Config(sdk = [35])`. Robolectric 4.14 chưa biết `targetSdk = 36`; không pin thì sandbox dựng ra thiếu các API đó và fail bằng `NoSuchMethodError` ngay trong constructor của test (`OfflineWatcherTest.kt` là ví dụ).
- Không có `AndroidKeyStore` provider dưới Robolectric. Bất kỳ code nào chạm `SettingsStore` (dùng `MasterKey`/`EncryptedSharedPreferences`, `settings/SettingsStore.kt`) — kể cả gián tiếp, như `SettingsActivity.onCreate()` gọi `store.readOrDefault(...)` ngay đầu — sẽ fail bằng `KeyStoreException: AndroidKeyStore not found` khi dựng Activity thật qua `Robolectric.buildActivity(...).create()`. Không có test nào trong repo từng dựng `SettingsActivity` (hay bất kỳ Activity nào đọc `SettingsStore` trong `onCreate`) qua Robolectric vì lý do này. Tách phần logic muốn test ra một hàm thuần nhận input trực tiếp (xem `pairingPayloadFrom(data: Intent)` ở `settings/PairActivity.kt` — nhận thẳng `Intent`, không cần dựng Activity) thay vì cố mock/fake Keystore.

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

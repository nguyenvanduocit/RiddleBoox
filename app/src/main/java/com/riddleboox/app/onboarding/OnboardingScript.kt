package com.riddleboox.app.onboarding

/**
 * Thứ tự cố định — có tính sư phạm, không random như
 * [com.riddleboox.app.riddle.GREETINGS]. Giọng văn nhất quán với
 * DEFAULT_AGENT_GREETINGS (Agent.kt:282-293): "Ta" xưng hô cổ, gọi người
 * dùng là "ngươi".
 */
val ONBOARDING_SEGMENTS: List<String> = listOf(
    "Ta là một cuốn nhật ký, nhưng không câm lặng như những cuốn khác. " +
        "Ngươi viết bằng bút lên đây, ta sẽ viết lại bằng chính nét chữ của mình.",
    "Ngừng bút một lúc, ta sẽ hiểu là ngươi đã viết xong và tự trả lời. " +
        "Muốn tự tay trao trang cho ta thì chạm vào chữ 'gửi' trên đầu trang.",
    "Chạm 'trang mới' khi ngươi muốn bắt đầu một chuyện khác — " +
        "ta sẽ không mang chuyện cũ ra so sánh, dù vẫn nhớ nó.",
    "Mỗi buổi trò chuyện đều được ta cất lại. Chạm 'lịch sử' để tìm và mở lại " +
        "bất cứ trang nào ngươi từng viết.",
    "Ta có nhiều gương mặt khác nhau — có gương mặt còn đọc được cả những cuốn sách " +
        "ngươi đang đọc dở. Chạm vào tên ở góc phải để chọn ai sẽ lắng nghe ngươi.",
    "Nếu chữ ta viết ra quá nhỏ, hay ngươi muốn đổi nơi ta lấy trí khôn của mình, " +
        "mọi thứ đều nằm trong 'settings'.",
)

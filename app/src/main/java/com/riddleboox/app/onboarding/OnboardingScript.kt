package com.riddleboox.app.onboarding

/**
 * Thứ tự cố định — có tính sư phạm, không random như
 * [com.riddleboox.app.riddle.GREETINGS]. Giọng văn nhất quán với
 * DEFAULT_AGENT_GREETINGS (Agent.kt): cuốn nhật ký xưng "I", gọi người
 * viết là "you", giọng cổ kính và hơi trang trọng.
 */
val ONBOARDING_SEGMENTS: List<String> = listOf(
    "I am a diary, but not a silent one like the others. " +
        "You write on me with your pen, and I write back in a hand of my own.",
    "Rest your pen a moment and I will take it that you are done, and answer. " +
        "To hand me the page yourself, touch the word 'send' at the top.",
    "Touch 'new conversation' when you want to speak of something else — " +
        "I will not weigh the new against the old, though I still remember it.",
    "Every conversation is kept. Touch 'history' to find and reopen " +
        "any page you have ever written.",
    "I wear more than one face — one of them can even read the books " +
        "you have left half-read. Touch the name in the right corner to choose who listens.",
    "If my writing comes out too small, or you would change where I draw my wits from, " +
        "everything lives in 'settings'.",
)

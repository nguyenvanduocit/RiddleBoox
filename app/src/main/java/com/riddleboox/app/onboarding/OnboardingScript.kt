package com.riddleboox.app.onboarding

/**
 * Six pages, in a fixed order that teaches — not drawn at random like
 * [com.riddleboox.app.riddle.GREETINGS]. Same voice as DEFAULT_AGENT_GREETINGS
 * (Agent.kt): the diary says "I", the writer is "you", the words are plain
 * and a little old.
 *
 * Each page answers what a first-time writer is wondering at that moment:
 * what this is and that the pages turn by themselves; how an answer comes and
 * how to stop it; what the small words along the top do; where the past is;
 * who is listening; and, last, that the intro is over and the page is theirs.
 * The labels in quotes are the chrome's own words, so a page never sends the
 * writer looking for a word that is not on the screen — 'send' is not named,
 * because it is hidden in the default (automatic) send mode.
 *
 * Invariants live in OnboardingScriptTest: six pages, page 5 is the books page
 * (the folder-grant ask follows it — MainActivity's
 * ONBOARDING_PERMISSION_CHECKPOINT), each page fits one sheet at the largest
 * reply font, every quoted label exists on the chrome.
 */
val ONBOARDING_SEGMENTS: List<String> = listOf(
    "I am a diary, but not a silent one. You write on me with your pen, and I " +
        "write back in a hand of my own. Six short pages first, then the page is yours.",
    "When you have written, rest your pen. After about three quiet seconds I take the " +
        "page and answer. While I answer, 'stop' appears at the top; touch it and I fall silent.",
    "Once the page is yours, small words sit along the top. 'new conversation', at the left, clears " +
        "the page for a new subject; I keep the old. 'memorize' asks me to keep what mattered for later.",
    "Every conversation is kept. Touch 'history', up on " +
        "the right, to reopen any of them and go on writing.",
    "I wear more than one face. The name beside 'history' is who listens now; touch it for " +
        "another. One, the librarian, reads the books you keep here. Before my last page I may ask to see them.",
    "The rest is in 'settings', at the far right: the size of my writing, my pen, this introduction " +
        "again. That is all. The page clears, the small words appear, I greet you, and the pen is yours.",
)

package com.riddleboox.app.reply

/**
 * The reply as a quill would write it — markdown emphasis stripped, the words
 * kept.
 *
 * Chat models emphasise by habit, and a diary has no bold: the pen renders
 * every character it is given, so `**Chúa tể Bóng Tối**` reaches the page as
 * four asterisks around the title. Nothing downstream can tell them from ink
 * the writer would want.
 *
 * Deliberately not a markdown parser. Only the marks that actually turn up in
 * conversational replies are handled, and only when they wrap something —
 * `2 * 3` and a lone hyphen stay exactly as written.
 */
fun plainText(reply: String): String {
    var text = reply
    for (mark in MARKS) {
        text = mark.replace(text) { match -> match.groupValues[1] }
    }
    return text.trim()
}

/**
 * Paired emphasis around at least one non-space character, longest mark first
 * so `**bold**` is not read as two empty italics.
 */
private val MARKS = listOf(
    Regex("""\*\*\*(\S(?:.*?\S)?)\*\*\*""", RegexOption.DOT_MATCHES_ALL),
    Regex("""\*\*(\S(?:.*?\S)?)\*\*""", RegexOption.DOT_MATCHES_ALL),
    Regex("""(?<![\w*])\*(\S(?:.*?\S)?)\*(?![\w*])""", RegexOption.DOT_MATCHES_ALL),
    Regex("""(?<![\w_])__(\S(?:.*?\S)?)__(?![\w_])""", RegexOption.DOT_MATCHES_ALL),
    Regex("""(?<![\w_])_(\S(?:.*?\S)?)_(?![\w_])""", RegexOption.DOT_MATCHES_ALL),
    Regex("""`([^`]+)`"""),
)

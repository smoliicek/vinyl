package com.smoliicek.vinyl

/** A title split into the part worth reading and a trailing qualifier like "- Remastered 2009". */
data class SplitTitle(val main: String, val suffix: String?)

// Words that mark a trailing " - …" or "(…)" as release noise rather than part of the name.
private val QUALIFIER = Regex(
    "\\b(remaster(ed)?|live|edition|version|edit|mix|remix|mono|stereo|acoustic|demo|bonus|deluxe|" +
        "anniversary|instrumental|explicit|clean|single|extended|soundtrack|ost|reprise|unplugged)\\b",
    RegexOption.IGNORE_CASE,
)
private val FEATURING = Regex("^(feat\\.?|ft\\.?|featuring|with)\\s", RegexOption.IGNORE_CASE)
private val FROM = Regex("^from\\s", RegexOption.IGNORE_CASE)
private val DASHES = listOf(" - ", " – ", " — ")

/**
 * Peels recognised qualifiers off the end of [text], innermost last, so
 * "Old Town Road (feat. Billy Ray Cyrus) - Remix" becomes "Old Town Road" + "(feat. Billy Ray Cyrus) - Remix".
 * Anything not recognised stays in [SplitTitle.main]; nothing is ever dropped.
 */
fun splitTitle(text: String): SplitTitle {
    var main = text.trimEnd()
    while (true) {
        val cut = trailingBracketQualifier(main) ?: trailingDashQualifier(main) ?: break
        main = main.substring(0, cut).trimEnd()
    }
    if (main.isBlank() || main.length == text.trimEnd().length) return SplitTitle(text.trim(), null)
    return SplitTitle(main.trim(), text.substring(main.length).trim())
}

private fun trailingBracketQualifier(s: String): Int? {
    val close = s.lastOrNull() ?: return null
    val open = when (close) {
        ')' -> '('
        ']' -> '['
        else -> return null
    }
    var depth = 0
    for (i in s.indices.reversed()) {
        when (s[i]) {
            close -> depth++
            open -> if (--depth == 0) {
                val content = s.substring(i + 1, s.length - 1).trim()
                return i.takeIf { FEATURING.containsMatchIn(content) || QUALIFIER.containsMatchIn(content) }
            }
        }
    }
    return null
}

private fun trailingDashQualifier(s: String): Int? {
    val (index, dash) = DASHES.map { s.lastIndexOf(it) to it }.maxBy { it.first }
    if (index <= 0) return null
    val rest = s.substring(index + dash.length).trim()
    return index.takeIf { QUALIFIER.containsMatchIn(rest) || FROM.containsMatchIn(rest) || FEATURING.containsMatchIn(rest) }
}

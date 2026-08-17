package com.example.voicedial

/**
 * Rough Hebrew -> Latin phonetic transliteration, plus a fuzzy similarity score.
 * This lets us match an English speech-to-text guess (from our English Vosk model)
 * against real Hebrew song titles/filenames, without needing to rename any files.
 * It will never be perfect, but it's much better than requiring exact text matches.
 */
object Transliteration {

    // Maps each Hebrew letter to a rough Latin phonetic equivalent.
    private val letterMap = mapOf(
        'א' to "", 'ב' to "b", 'ג' to "g", 'ד' to "d", 'ה' to "h",
        'ו' to "v", 'ז' to "z", 'ח' to "ch", 'ט' to "t", 'י' to "y",
        'כ' to "k", 'ך' to "k", 'ל' to "l", 'מ' to "m", 'ם' to "m",
        'נ' to "n", 'ן' to "n", 'ס' to "s", 'ע' to "", 'פ' to "p",
        'ף' to "f", 'צ' to "tz", 'ץ' to "tz", 'ק' to "k", 'ר' to "r",
        'ש' to "sh", 'ת' to "t"
    )

    fun hebrewToLatin(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            sb.append(letterMap[c] ?: c)
        }
        return sb.toString().lowercase()
    }

    /** Normalizes a string for fuzzy comparison: transliterate if Hebrew, strip non-letters. */
    fun normalize(text: String): String {
        val hasHebrew = text.any { it in '\u0590'..'\u05FF' }
        val base = if (hasHebrew) hebrewToLatin(text) else text.lowercase()
        return base.filter { it.isLetterOrDigit() }
    }

    /** Classic Levenshtein edit distance between two strings. */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)

        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            for (j in 0..b.length) prev[j] = curr[j]
        }
        return prev[b.length]
    }

    /**
     * Similarity score between 0.0 (no match) and 1.0 (identical), after normalizing
     * both strings (transliterating Hebrew to Latin phonetics first).
     */
    fun similarity(query: String, candidate: String): Double {
        val a = normalize(query)
        val b = normalize(candidate)
        if (a.isEmpty() || b.isEmpty()) return 0.0

        if (b.contains(a) || a.contains(b)) return 0.85

        val dist = levenshtein(a, b)
        val maxLen = maxOf(a.length, b.length)
        return 1.0 - (dist.toDouble() / maxLen)
    }
}

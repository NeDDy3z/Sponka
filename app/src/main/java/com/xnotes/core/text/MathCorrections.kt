package com.xnotes.core.text

/** How often this user meant which token when a math model read another, used to fix and vary results. */
class MathCorrections {

    /** recognized token -> meant token -> times seen; meant == recognized counts a correct read. */
    private val seen = HashMap<String, HashMap<String, Int>>()

    fun learn(recognized: String, meant: String) {
        for ((r, m) in pairs(recognized, meant)) seen.getOrPut(r) { HashMap() }.merge(m, 1, Int::plus)
    }

    /** [latex] with every token swapped for what this user usually means by it. */
    fun apply(latex: String): String = RecognizedLatex.join(RecognizedLatex.tokens(latex).map { best(it) ?: it })

    /**
     * Up to [max] other readings of the model's [raw] output, each [apply]'s result with one token
     * changed to another reading seen for it (or back to the model's own), most often seen first.
     */
    fun variants(raw: String, max: Int = 3): List<String> {
        val toks = RecognizedLatex.tokens(raw)
        val fixed = toks.map { best(it) ?: it }
        val options = ArrayList<Triple<Int, String, Int>>()
        for ((i, t) in toks.withIndex()) {
            val row = seen[t].orEmpty()
            for (meant in row.keys + t + LOOKALIKES[t].orEmpty()) if (meant != fixed[i]) options += Triple(i, meant, row[meant] ?: 0)
        }
        val applied = RecognizedLatex.join(fixed)
        return options.sortedByDescending { it.third }
            .map { (i, meant, _) -> RecognizedLatex.join(fixed.toMutableList().also { it[i] = meant }) }
            .distinct()
            .filter { it != applied }
            .take(max)
    }

    /** A swap is trusted once seen twice and more often than the token was read right. */
    private fun best(token: String): String? {
        val m = seen[token] ?: return null
        val right = m[token] ?: 0
        val (meant, n) = m.entries.filter { it.key != token }.maxByOrNull { it.value } ?: return null
        return meant.takeIf { n >= 2 && n > right }
    }

    /** The top reading of [candidates] (the model's, best first) after corrections, and others to offer. */
    class Suggestion(val raw: String, val best: String, val variants: List<String>)

    /** Up to [want] or more alternatives: other readings, the uncorrected top one, then single-token swaps. */
    fun suggest(candidates: List<String>, renders: (String) -> Boolean, want: Int = 3): Suggestion? {
        val raw = candidates.firstOrNull() ?: return null
        val best = apply(raw)
        val others = candidates.drop(1)
        val pool = others.map { apply(it) } + raw + others + variants(raw, want * 3)
        return Suggestion(raw, best, pool.distinct().filter { it != best && renders(it) }.take(maxOf(want, 5)))
    }

    companion object {

        /** Symbols handwriting confuses whatever the writer, offered as variants before anything is learned. */
        private val LOOKALIKES: Map<String, List<String>> = mapOf(
            "x" to listOf("\\times", "X", "\\chi"), "X" to listOf("x"), "\\times" to listOf("x"),
            "1" to listOf("l", "|", "I"), "l" to listOf("1", "|"), "|" to listOf("1", "l"), "I" to listOf("1"),
            "0" to listOf("o", "O", "\\theta"), "o" to listOf("0", "\\circ"), "O" to listOf("0"),
            "2" to listOf("z", "Z"), "z" to listOf("2"), "Z" to listOf("2"),
            "5" to listOf("s", "S"), "s" to listOf("5"), "S" to listOf("5"),
            "9" to listOf("g", "q"), "g" to listOf("9"), "q" to listOf("9"),
            "8" to listOf("B"), "B" to listOf("8", "\\beta"),
            "a" to listOf("\\alpha"), "\\alpha" to listOf("a"),
            "u" to listOf("\\mu", "v"), "v" to listOf("\\nu", "u"), "n" to listOf("\\eta"),
            "e" to listOf("\\epsilon"), "p" to listOf("\\rho"), "w" to listOf("\\omega"), "y" to listOf("\\gamma"),
            "t" to listOf("+"), "+" to listOf("t"),
            "<" to listOf("\\leq"), ">" to listOf("\\geq"), "\\leq" to listOf("<"), "\\geq" to listOf(">"),
        )

        /** Token pairs from a Levenshtein alignment; insertions are skipped and a rewrite teaches nothing. */
        internal fun pairs(recognized: String, meant: String): List<Pair<String, String>> {
            val a = RecognizedLatex.tokens(recognized)
            val b = RecognizedLatex.tokens(meant)
            if (a.isEmpty() || b.isEmpty()) return emptyList()
            val d = Array(a.size + 1) { IntArray(b.size + 1) }
            for (i in 0..a.size) d[i][0] = i
            for (j in 0..b.size) d[0][j] = j
            for (i in 1..a.size) for (j in 1..b.size) {
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val distance = d[a.size][b.size]
            if (distance > 1 && distance * 2 > maxOf(a.size, b.size)) return emptyList()
            val out = ArrayList<Pair<String, String>>()
            var i = a.size
            var j = b.size
            while (i > 0 && j > 0) {
                when (d[i][j]) {
                    d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1 -> { out += a[i - 1] to b[j - 1]; i--; j-- }
                    d[i - 1][j] + 1 -> i--
                    else -> j--
                }
            }
            return out.asReversed()
        }
    }
}

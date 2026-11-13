package dev.bookah.photos.search

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.BreakIterator

/**
 * The search model's tokenizer, ported line by line from the Python reference `phone_tokenizer.py` in the indexer (which reproduces the PC's
 * Hugging Face tokenizer exactly). A typed question becomes the 77 numbers the text model reads. Three steps:
 *
 * 1. Normalizer: split into grapheme clusters; a cluster shorter than 6 bytes is looked up as a whole in the charsmap trie and the FIRST match is used,
 *    otherwise every code point on its own.
 * 2. Split on white space and put U+2581 in front of every word.
 * 3. Unigram: Viterbi over every vocabulary piece (up to the longest piece) plus one <unk> where nothing matches; the first best path stays on ties;
 *    consecutive <unk> are fused. Then <s> ... </s>, cut to 77 and padded with <pad>.
 *
 * File format of the phone asset: see the Python module docstring. Nothing here needs Android, so it runs in plain JVM tests.
 */
class PhoneTokenizer private constructor(
    private val units: IntArray,
    private val normalized: ByteArray,
    private val vocab: HashMap<String, Int>,
    private val scores: DoubleArray,
    private val mapped: IntArray,
    private val maxPieceLen: Int,
) {
    private val unkScore = (scores.minOrNull() ?: 0.0) - UNK_PENALTY

    // ---- 1. normalizer ----
    private fun offset(unit: Int): Int = (unit ushr 10) shl ((unit and 0x200) ushr 6)

    /** The value of the first (shortest) charsmap match among the prefixes of [key], or -1. */
    private fun firstMatch(key: ByteArray): Int {
        var node = 0
        if (units.isEmpty()) return -1
        node = node xor offset(units[node])
        for (b in key) {
            val c = b.toInt() and 0xFF
            if (c == 0) break
            node = node xor c
            if (node < 0 || node >= units.size) return -1
            val unit = units[node]
            if ((unit and (Int.MIN_VALUE or 0xFF)) != c) return -1
            node = node xor offset(unit)
            if (((unit ushr 8) and 1) == 1) {
                if (node < 0 || node >= units.size) return -1
                return units[node] and 0x7FFFFFFF
            }
        }
        return -1
    }

    private fun entry(index: Int): String {
        var end = index
        while (end < normalized.size && normalized[end] != 0.toByte()) end++
        return String(normalized, index, end - index, Charsets.UTF_8)
    }

    private fun transform(chunk: String): String? {
        val v = firstMatch(chunk.toByteArray(Charsets.UTF_8))
        return if (v < 0) null else entry(v)
    }

    fun normalize(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length + 8)
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val g = text.substring(start, end)
            var done = false
            if (g.toByteArray(Charsets.UTF_8).size < 6) {
                val t = transform(g)
                if (t != null) { out.append(t); done = true }
            }
            if (!done) {
                var i = 0
                while (i < g.length) {
                    val cp = g.codePointAt(i)
                    val ch = String(Character.toChars(cp))
                    out.append(transform(ch) ?: ch)
                    i += Character.charCount(cp)
                }
            }
            start = end
            end = it.next()
        }
        return out.toString()
    }

    // ---- 3. unigram, for one word (already prefixed with U+2581) given as code points ----
    private fun viterbi(word: IntArray): IntArray {
        val n = word.size
        val nodeBegin = ArrayList<Int>(n * 4 + 2); val nodeId = ArrayList<Int>(n * 4 + 2); val nodeScore = ArrayList<Double>(n * 4 + 2)
        val beginNodes = Array(n + 1) { ArrayList<Int>() }
        val endNodes = Array(n + 1) { ArrayList<Int>() }
        nodeBegin.add(-1); nodeId.add(BOS); nodeScore.add(0.0)                       // node 0 = BOS
        endNodes[0].add(0)
        for (b in 0 until n) {
            var hasSingle = false
            val maxL = minOf(maxPieceLen, n - b)
            for (l in 1..maxL) {
                val pid = vocab[String(word, b, l)]
                if (pid != null) {
                    if (l == 1) hasSingle = true
                    val k = nodeId.size
                    nodeBegin.add(b); nodeId.add(pid); nodeScore.add(scores[pid])
                    beginNodes[b].add(k); endNodes[b + l].add(k)
                }
            }
            if (!hasSingle) {
                val k = nodeId.size
                nodeBegin.add(b); nodeId.add(UNK); nodeScore.add(unkScore)
                beginNodes[b].add(k); endNodes[b + 1].add(k)
            }
        }
        val eos = nodeId.size
        nodeBegin.add(n); nodeId.add(-1); nodeScore.add(0.0)
        beginNodes[n].add(eos)
        val back = IntArray(nodeId.size) { -1 }
        val best = DoubleArray(nodeId.size)
        for (pos in 0..n) {
            for (k in beginNodes[pos]) {
                var bestNode = -1
                var bs = 0.0
                for (e in endNodes[pos]) {
                    val s = best[e] + nodeScore[k]
                    if (bestNode == -1 || s > bs) { bestNode = e; bs = s }          // strictly better wins: on a tie the first stays
                }
                best[k] = bs
                back[k] = bestNode
            }
        }
        val path = ArrayList<Int>()
        var k = back[eos]
        while (k != 0) { path.add(k); k = back[k] }
        path.reverse()
        val ids = ArrayList<Int>(path.size)
        var lastUnk = false
        for (node in path) {
            val isUnk = nodeId[node] == UNK
            if (!(isUnk && lastUnk)) ids.add(nodeId[node])                           // fuse consecutive <unk>
            lastUnk = isUnk
        }
        return ids.toIntArray()
    }

    // ---- the whole pipeline ----
    /** Piece ids (already mapped for the small text model) without <s>, </s>, cutting or padding. */
    fun encodeRaw(text: String): IntArray {
        val normalizedText = normalize(text)
        val out = ArrayList<Int>()
        var i = 0
        val word = ArrayList<Int>()
        fun flush() {
            if (word.isEmpty()) return
            val cps = IntArray(word.size + 1)
            cps[0] = SPACE_CP
            for (j in word.indices) cps[j + 1] = word[j]
            for (id in viterbi(cps)) out.add(mapped[id])
            word.clear()
        }
        while (i < normalizedText.length) {
            val cp = normalizedText.codePointAt(i)
            if (isWhitespace(cp)) flush() else word.add(cp)
            i += Character.charCount(cp)
        }
        flush()
        return out.toIntArray()
    }

    /** The model input: [<s>, pieces..., </s>] cut to 77 (keeping the closing </s>) and padded with <pad>. */
    fun encode(text: String): IntArray {
        val raw = encodeRaw(text)
        val ids = IntArray(SEQ) { PAD }
        val total = raw.size + 2
        if (total <= SEQ) {
            ids[0] = BOS
            for (j in raw.indices) ids[j + 1] = raw[j]
            ids[raw.size + 1] = EOS
        } else {
            ids[0] = BOS
            for (j in 0 until SEQ - 2) ids[j + 1] = raw[j]
            ids[SEQ - 1] = EOS
        }
        return ids
    }

    companion object {
        const val SEQ = 77
        const val PAD = 1
        const val BOS = 0
        const val EOS = 2
        const val UNK = 3
        private const val SPACE_CP = 0x2581
        private const val UNK_PENALTY = 10.0

        // Unicode White_Space: what WhitespaceSplit (Rust char::is_whitespace) splits on
        private fun isWhitespace(cp: Int): Boolean = when (cp) {
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
            else -> false
        }

        fun load(file: File): PhoneTokenizer = parse(ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN))

        fun parse(b: ByteBuffer): PhoneTokenizer {
            require(b.get().toInt().toChar() == 'B' && b.get().toInt().toChar() == 'K' && b.get().toInt().toChar() == 'T' && b.get().toInt().toChar() == 'K') { "not a tokenizer file" }
            val version = b.int
            require(version == 1) { "unsupported tokenizer file version $version" }
            val charsmapLen = b.int
            val trieBytes = b.int                                                    // the charsmap starts with its own trie size
            val units = IntArray(trieBytes / 4) { b.int }
            val normalized = ByteArray(charsmapLen - 4 - trieBytes)
            b.get(normalized)
            val n = b.int
            val vocab = HashMap<String, Int>(n * 2)
            val scores = DoubleArray(n)
            val mapped = IntArray(n)
            var maxLen = 1
            val buf = ByteArray(255)
            for (i in 0 until n) {
                val len = b.get().toInt() and 0xFF
                b.get(buf, 0, len)
                val piece = String(buf, 0, len, Charsets.UTF_8)
                scores[i] = b.float.toDouble()
                mapped[i] = b.int
                vocab[piece] = i
                val cps = piece.codePointCount(0, piece.length)
                if (cps > maxLen) maxLen = cps
            }
            return PhoneTokenizer(units, normalized, vocab, scores, mapped, maxLen)
        }
    }
}

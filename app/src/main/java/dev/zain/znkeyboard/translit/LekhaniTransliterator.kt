package dev.zain.znkeyboard.translit

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.zip.GZIPInputStream

/**
 * Romanised Hindi -> Devanagari with the Lekhani model (https://huggingface.co/udgamlabs/Lekhani):
 * a port of the plain-JS path of its translit.js runner. A tiny encoder-decoder runs a beam search,
 * then the finished candidates are reranked by model log-prob / length + lam * banded log p(word)
 * from the bundled word list.
 *
 * Weights are stored as f32 and sums run in f64, rounding to f32 wherever the JS stores into a
 * Float32Array, and exp/log/sin/cos use StrictMath (fdlibm, as V8), so results match the runner;
 * tools/translit/verify_lekhani.sh checks that against its golden output.
 *
 * Not thread-safe: the scratch buffers and the cache are shared, so use one instance from a single
 * background thread.
 */
class LekhaniTransliterator private constructor(
    manifest: Map<*, *>,
    raw: ByteArray,
    private val list: WordList?,
    rerank: Map<*, *>?,
) {
    private val d: Int
    private val h: Int
    private val f: Int
    private val tgt: List<String>
    private val vocab: Int
    private val sidx = HashMap<Int, Int>()
    private val lam = (rerank?.get("lam") as? Double) ?: 0.0
    private val off = (rerank?.get("off") as? Double) ?: 0.0
    private val beam = ((rerank?.get("beam") as? Double) ?: 4.0).toInt()

    private val se: FloatArray
    private val te: FloatArray
    private val enc: List<EncoderLayer>
    private val dec: List<DecoderLayer>
    private val eln: Norm
    private val dln: Norm
    private val pos: FloatArray
    private val sqrtD: Double
    private val scale: Double

    // scratch rows: x the residual stream, n a LayerNorm output, a attention outputs, q cross-attention
    // queries, hid the wider Linear outputs (qkv, fc1, logits)
    private val x: FloatArray
    private val n: FloatArray
    private val a: FloatArray
    private val q: FloatArray
    private val hid: FloatArray
    private val xd: DoubleArray
    private val encK: FloatArray
    private val encV: FloatArray
    private val crossK: Array<FloatArray>
    private val crossV: Array<FloatArray>
    private val sc = DoubleArray(maxOf(CAP, MAX_LEN + 1))
    private val logp: DoubleArray

    // beam state, double-buffered: [buffer][hypothesis] (and [layer] for the self-attention caches)
    private val cacheK: Array<Array<Array<FloatArray>>>
    private val cacheV: Array<Array<Array<FloatArray>>>
    private val seqs: Array<Array<IntArray>>
    private val scores: Array<DoubleArray>
    private val live: IntArray
    private val stepToks: IntArray
    private val topS: DoubleArray
    private val topI: IntArray
    private val nextR: IntArray
    private val nextT: IntArray
    private val nextS: DoubleArray
    private var encLen = 0

    private val cache = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > CACHE_SIZE
    }

    init {
        val config = manifest["config"] as Map<*, *>
        fun cfg(key: String) = (config[key] as Double).toInt()
        d = cfg("d"); h = cfg("h"); f = cfg("f")
        val le = cfg("le")
        val ld = cfg("ld")
        (manifest["src"] as List<*>).forEachIndexed { i, s ->
            val str = s as String
            if (str.codePointCount(0, str.length) == 1) sidx[str.codePointAt(0)] = i
        }
        tgt = (manifest["tgt"] as List<*>).map { it as String }
        vocab = tgt.size
        require(beam in 1..CAP) { "beam $beam is outside 1..$CAP" }

        val w = loadWeights(manifest, raw)
        fun t(name: String) = w[name] ?: error("missing tensor $name")
        fun norm(p: String) = Norm(t("$p.weight"), t("$p.bias"))
        fun lin(p: String) = Linear(t("$p.weight"), t("$p.bias"))
        se = t("se.weight"); te = t("te.weight")
        enc = List(le) { l ->
            val p = "enc.$l."
            EncoderLayer(norm(p + "ln1"), lin(p + "qkv"), lin(p + "o"), Ffn(norm(p + "ln3"), lin(p + "fc1"), lin(p + "fc2")))
        }
        dec = List(ld) { l ->
            val p = "dec.$l."
            DecoderLayer(
                norm(p + "ln1"), lin(p + "qkv"), lin(p + "o"), norm(p + "ln2"), lin(p + "q2"), lin(p + "kv2"), lin(p + "o2"),
                Ffn(norm(p + "ln3"), lin(p + "fc1"), lin(p + "fc2")),
            )
        }
        eln = norm("eln"); dln = norm("dln")

        val half = d / 2
        val e = StrictMath.log(10000.0) / (half - 1)
        pos = FloatArray(64 * d)
        for (p in 0 until 64) for (i in 0 until half) {
            if (p == 1) continue
            pos[p * d + i] = StrictMath.sin(p * StrictMath.exp(-i * e)).toFloat()
            pos[p * d + half + i] = StrictMath.cos(p * StrictMath.exp(-i * e)).toFloat()
        }
        sqrtD = Math.sqrt(d.toDouble())
        scale = 1 / Math.sqrt((d / h).toDouble())

        val wide = maxOf(3 * d, f, vocab)
        x = FloatArray(CAP * d); n = FloatArray(CAP * d); a = FloatArray(CAP * d); q = FloatArray(CAP * d)
        hid = FloatArray(CAP * wide)
        xd = DoubleArray(CAP * wide)
        encK = FloatArray(CAP * d); encV = FloatArray(CAP * d)
        crossK = Array(ld) { FloatArray(CAP * d) }; crossV = Array(ld) { FloatArray(CAP * d) }
        logp = DoubleArray(beam * vocab)
        cacheK = Array(2) { Array(beam) { Array(ld) { FloatArray((MAX_LEN + 1) * d) } } }
        cacheV = Array(2) { Array(beam) { Array(ld) { FloatArray((MAX_LEN + 1) * d) } } }
        seqs = Array(2) { Array(beam) { IntArray(MAX_LEN + 2) } }
        scores = Array(2) { DoubleArray(beam) }
        live = IntArray(beam); stepToks = IntArray(beam)
        topS = DoubleArray(2 * beam); topI = IntArray(2 * beam)
        nextR = IntArray(beam); nextT = IntArray(beam); nextS = DoubleArray(beam)
    }

    /** Ranked Devanagari spellings of one romanised word, best first, without duplicates. */
    fun candidates(word: String, max: Int = 8): List<String> {
        val src = word.trim().lowercase()
        if (src.isEmpty() || max <= 0) return emptyList()
        val ranked = cache[src] ?: rank(src).map { it.text }.filter { it.isNotEmpty() }.distinct().also { cache[src] = it }
        return if (ranked.size <= max) ranked else ranked.subList(0, max)
    }

    fun best(word: String): String = candidates(word, 1).firstOrNull() ?: ""

    /** Uncached (text, final score) pairs in candidate order, duplicates included; for the harness. */
    internal fun scored(word: String): List<Pair<String, Double>> {
        val src = word.trim().lowercase()
        return if (src.isEmpty()) emptyList() else rank(src).map { it.text to it.final }
    }

    private class Hyp(val text: String, val score: Double) {
        var final = score
    }

    private fun rank(src: String): List<Hyp> {
        val nb = nbest(src)
        if (list != null && lam != 0.0) {
            for (c in nb) c.final = c.score + lam * (list.logp(c.text) ?: (list.floor - off))
        }
        return nb.sortedWith(descending { it.final })
    }

    // beam search with the reference's rules; finished hypotheses, best model score first
    private fun nbest(word: String): List<Hyp> {
        val k = beam
        val v = vocab
        val chars = word.take(61)
        val toks = IntArray(chars.codePointCount(0, chars.length) + 1)
        var t = 0
        var i = 0
        while (i < chars.length) {
            val cp = chars.codePointAt(i)
            toks[t++] = sidx[cp] ?: UNK
            i += Character.charCount(cp)
        }
        toks[t] = EOS
        encode(toks)

        var cur = 0
        scores[cur][0] = 0.0
        for (r in 1 until k) scores[cur][r] = Double.NEGATIVE_INFINITY
        seqs[cur][0][0] = EOS
        var len = 1
        val fin = ArrayList<Hyp>(k)
        for (step in 0 until MAX_LEN) {
            val sl = len
            var b = 0
            for (r in 0 until k) if (scores[cur][r] != Double.NEGATIVE_INFINITY) live[b++] = r
            for (j in 0 until b) stepToks[j] = seqs[cur][live[j]][sl - 1]
            step(b, sl, cur)
            // top 2K kept sorted as candidates arrive in flat-index order; equal scores keep the lower index first
            var cnt = 0
            for (j in 0 until b) {
                val r = live[j]
                val base = j * v
                val hs = scores[cur][r]
                logp[base + PAD] = Double.NEGATIVE_INFINITY
                logp[base] = Double.NEGATIVE_INFINITY
                logp[base + UNK] = Double.NEGATIVE_INFINITY
                if (step == 0) logp[base + EOS] = Double.NEGATIVE_INFINITY
                for (tok in 0 until v) {
                    val lp = logp[base + tok]
                    if (!(lp > Double.NEGATIVE_INFINITY)) continue
                    val s = hs + lp
                    if (cnt == 2 * k && !(s > topS[cnt - 1])) continue
                    var p = if (cnt < 2 * k) cnt++ else cnt - 1
                    while (p > 0 && topS[p - 1] < s) {
                        topS[p] = topS[p - 1]; topI[p] = topI[p - 1]; p--
                    }
                    topS[p] = s; topI[p] = r * v + tok
                }
            }
            var m = 0
            for (j in 0 until cnt) {
                val r = topI[j] / v
                val tok = topI[j] % v
                if (tok == EOS) {
                    if (j < k && fin.size < k) fin.add(Hyp(text(seqs[cur][r], sl), topS[j] / sl))
                    continue
                }
                if (m < k) {
                    nextR[m] = r; nextT[m] = tok; nextS[m] = topS[j]; m++
                }
            }
            if (fin.size >= k || m == 0) break
            val nxt = 1 - cur
            for (j in 0 until m) {
                val r = nextR[j]
                seqs[cur][r].copyInto(seqs[nxt][j], 0, 0, sl)
                seqs[nxt][j][sl] = nextT[j]
                scores[nxt][j] = nextS[j]
                for (l in dec.indices) {
                    cacheK[cur][r][l].copyInto(cacheK[nxt][j][l], 0, 0, sl * d)
                    cacheV[cur][r][l].copyInto(cacheV[nxt][j][l], 0, 0, sl * d)
                }
            }
            for (j in m until k) scores[nxt][j] = Double.NEGATIVE_INFINITY
            len = sl + 1
            cur = nxt
        }
        return fin.sortedWith(descending { it.score })
    }

    private fun text(seq: IntArray, len: Int): String {
        val sb = StringBuilder()
        for (i in 1 until len) if (seq[i] > 3) sb.append(tgt[seq[i]])
        return Normalizer.normalize(sb, Normalizer.Form.NFC).replace("\u200C", "").replace("\u200D", "")
    }

    // all S positions go through each layer together
    private fun encode(toks: IntArray) {
        val s = toks.size
        encLen = s
        for (i in 0 until s) embed(se, toks[i], i + 2, i * d)
        for (layer in enc) {
            layerNorm(x, layer.ln1, n, s)
            linear(layer.qkv.w, layer.qkv.b, n, hid, s, d, 3 * d, false)
            split(3 * d, d, s, encK, encV)
            for (i in 0 until s) attend(hid, i * 3 * d, encK, encV, s, a, i * d)
            linear(layer.o.w, layer.o.b, a, x, s, d, d, true)
            ffn(layer.ffn, s)
        }
        layerNorm(x, eln, n, s)
        for (l in dec.indices) {
            linear(dec[l].kv2.w, dec[l].kv2.b, n, hid, s, d, 2 * d, false)
            split(2 * d, 0, s, crossK[l], crossV[l])
        }
    }

    // keys and values out of the rows of hid (w wide, keys at column c and values right after)
    private fun split(w: Int, c: Int, s: Int, kOut: FloatArray, vOut: FloatArray) {
        for (i in 0 until s) {
            hid.copyInto(kOut, i * d, i * w + c, i * w + c + d)
            hid.copyInto(vOut, i * d, i * w + c + d, i * w + c + 2 * d)
        }
    }

    // one decoder step for the b live hypotheses at once; log-probs go to logp, row j for live[j]
    private fun step(b: Int, len: Int, cur: Int) {
        for (j in 0 until b) embed(te, stepToks[j], len + 1, j * d)
        for (l in dec.indices) {
            val layer = dec[l]
            layerNorm(x, layer.ln1, n, b)
            linear(layer.qkv.w, layer.qkv.b, n, hid, b, d, 3 * d, false)
            for (j in 0 until b) {
                val ck = cacheK[cur][live[j]][l]
                val cv = cacheV[cur][live[j]][l]
                val o = j * 3 * d
                hid.copyInto(ck, (len - 1) * d, o + d, o + 2 * d)
                hid.copyInto(cv, (len - 1) * d, o + 2 * d, o + 3 * d)
                attend(hid, o, ck, cv, len, a, j * d)
            }
            linear(layer.o.w, layer.o.b, a, x, b, d, d, true)
            layerNorm(x, layer.ln2, n, b)
            linear(layer.q2.w, layer.q2.b, n, q, b, d, d, false)
            for (j in 0 until b) attend(q, j * d, crossK[l], crossV[l], encLen, a, j * d)
            linear(layer.o2.w, layer.o2.b, a, x, b, d, d, true)
            ffn(layer.ffn, b)
        }
        // output layer tied to the target embedding, then log-softmax per row
        layerNorm(x, dln, n, b)
        linear(te, null, n, hid, b, d, vocab, false)
        for (j in 0 until b) {
            val base = j * vocab
            var mx = Double.NEGATIVE_INFINITY
            for (t in 0 until vocab) {
                val lg = hid[base + t].toDouble()
                logp[base + t] = lg
                if (lg > mx) mx = lg
            }
            var sum = 0.0
            for (t in 0 until vocab) sum += StrictMath.exp(logp[base + t] - mx)
            val lz = mx + StrictMath.log(sum)
            for (t in 0 until vocab) logp[base + t] -= lz
        }
    }

    private fun embed(table: FloatArray, tok: Int, p: Int, o: Int) {
        for (i in 0 until d) x[o + i] = (table[tok * d + i] * sqrtD + pos[p * d + i]).toFloat()
    }

    private fun ffn(layer: Ffn, b: Int) {
        layerNorm(x, layer.ln, n, b)
        linear(layer.fc1.w, layer.fc1.b, n, hid, b, d, f, false)
        for (i in 0 until b * f) hid[i] = gelu(hid[i])
        linear(layer.fc2.w, layer.fc2.b, hid, x, b, f, d, true)
    }

    // Y = X W^T + b for a Linear with weight [nout, nin] on rows rows; with acc the result is added to Y.
    // Each sum runs over i in order, as in the JS; two outputs by four rows at a time only give the CPU
    // eight independent sums and read each weight once per four rows
    private fun linear(
        w: FloatArray, b: FloatArray?, xf: FloatArray, y: FloatArray, rows: Int, nin: Int, nout: Int, acc: Boolean,
    ) {
        val x = xd
        for (i in 0 until rows * nin) x[i] = xf[i].toDouble()
        var o = 0
        while (o < nout) {
            val pair = o + 1 < nout
            val wa = o * nin
            val wb = wa + nin
            val sa = if (b != null) b[o].toDouble() else 0.0
            val sb = if (b != null && pair) b[o + 1].toDouble() else 0.0
            var r = 0
            while (r + 4 <= rows) {
                val x0 = r * nin
                val x1 = x0 + nin
                val x2 = x1 + nin
                val x3 = x2 + nin
                var a0 = sa; var a1 = sa; var a2 = sa; var a3 = sa
                if (pair) {
                    var b0 = sb; var b1 = sb; var b2 = sb; var b3 = sb
                    for (i in 0 until nin) {
                        val u = w[wa + i].toDouble()
                        val v = w[wb + i].toDouble()
                        val p0 = x[x0 + i]; val p1 = x[x1 + i]; val p2 = x[x2 + i]; val p3 = x[x3 + i]
                        a0 += u * p0; a1 += u * p1; a2 += u * p2; a3 += u * p3
                        b0 += v * p0; b1 += v * p1; b2 += v * p2; b3 += v * p3
                    }
                    put(y, r * nout + o + 1, b0, acc); put(y, (r + 1) * nout + o + 1, b1, acc)
                    put(y, (r + 2) * nout + o + 1, b2, acc); put(y, (r + 3) * nout + o + 1, b3, acc)
                } else {
                    for (i in 0 until nin) {
                        val u = w[wa + i].toDouble()
                        a0 += u * x[x0 + i]; a1 += u * x[x1 + i]; a2 += u * x[x2 + i]; a3 += u * x[x3 + i]
                    }
                }
                put(y, r * nout + o, a0, acc); put(y, (r + 1) * nout + o, a1, acc)
                put(y, (r + 2) * nout + o, a2, acc); put(y, (r + 3) * nout + o, a3, acc)
                r += 4
            }
            while (r < rows) {
                val xo = r * nin
                var a0 = sa
                var b0 = sb
                if (pair) {
                    for (i in 0 until nin) {
                        val p = x[xo + i]
                        a0 += w[wa + i].toDouble() * p; b0 += w[wb + i].toDouble() * p
                    }
                    put(y, r * nout + o + 1, b0, acc)
                } else {
                    for (i in 0 until nin) a0 += w[wa + i].toDouble() * x[xo + i]
                }
                put(y, r * nout + o, a0, acc)
                r++
            }
            o += 2
        }
    }

    // one query row against s cached keys/values, h heads, softmax(q k / sqrt(hd)) v
    private fun attend(qv: FloatArray, qo: Int, kc: FloatArray, vc: FloatArray, s: Int, out: FloatArray, oo: Int) {
        val hd = d / h
        out.fill(0f, oo, oo + d)
        for (hh in 0 until h) {
            val o = hh * hd
            var mx = Double.NEGATIVE_INFINITY
            for (t in 0 until s) {
                var acc = 0.0
                val ko = t * d + o
                for (i in 0 until hd) acc += qv[qo + o + i].toDouble() * kc[ko + i]
                sc[t] = acc * scale
                if (sc[t] > mx) mx = sc[t]
            }
            var z = 0.0
            for (t in 0 until s) {
                sc[t] = StrictMath.exp(sc[t] - mx); z += sc[t]
            }
            for (t in 0 until s) {
                val p = sc[t] / z
                val vo = t * d + o
                for (i in 0 until hd) out[oo + o + i] = (out[oo + o + i] + p * vc[vo + i]).toFloat()
            }
        }
    }

    private class Norm(val w: FloatArray, val b: FloatArray)
    private class Linear(val w: FloatArray, val b: FloatArray)
    private class Ffn(val ln: Norm, val fc1: Linear, val fc2: Linear)
    private class EncoderLayer(val ln1: Norm, val qkv: Linear, val o: Linear, val ffn: Ffn)
    private class DecoderLayer(
        val ln1: Norm, val qkv: Linear, val o: Linear, val ln2: Norm, val q2: Linear, val kv2: Linear, val o2: Linear,
        val ffn: Ffn,
    )

    internal class WordList(raw: ByteArray, layout: String) {
        private val centres: FloatArray
        private val off: IntArray
        private val band: ByteArray
        private val bytes: ByteArray
        private val groups: IntArray
        val floor: Double
        val size: Int

        // front-coded entries decoded into full byte strings (one byte per Devanagari char, U+0900 + b - 0x80);
        // groups are sorted runs
        init {
            val bands = u32(raw, 0)
            val v2 = layout == "v2"
            val counts = if (v2) intArrayOf(u32(raw, 4)) else IntArray(bands) { u32(raw, 4 + 4 * it) }
            var p = if (v2) 8 else 4 + 4 * bands
            centres = FloatArray(bands) { Float.fromBits(u32(raw, p + 4 * it)) }
            p += 4 * bands
            val total = counts.sum()
            off = IntArray(total + 1)
            band = ByteArray(total)
            groups = IntArray(2 * counts.size)
            val start = p
            var len = 0
            for (j in 0 until total) {
                var e = p + 1
                while (raw[e].toInt() != 0) e++
                len += (raw[p].toInt() and 0xFF) + e - p - 1
                p = e + 1
            }
            bytes = ByteArray(len)
            p = start
            var k = 0
            len = 0
            counts.forEachIndexed { gi, count ->
                groups[2 * gi] = k
                var prev = -1
                for (j in 0 until count) {
                    val pre = raw[p].toInt() and 0xFF
                    var e = p + 1
                    while (raw[e].toInt() != 0) e++
                    off[k] = len
                    if (prev >= 0) bytes.copyInto(bytes, len, prev, prev + pre)
                    raw.copyInto(bytes, len + pre, p + 1, e)
                    prev = len
                    len += pre + e - p - 1
                    if (!v2) band[k] = gi.toByte()
                    p = e + 1
                    k++
                }
                groups[2 * gi + 1] = k
            }
            off[total] = len
            if (v2) raw.copyInto(band, 0, p, p + total)
            var fl = Float.POSITIVE_INFINITY
            for (i in 0 until total) fl = minOf(fl, centres[band[i].toInt() and 0xFF])
            floor = fl.toDouble()
            size = total
        }

        private fun cmp(i: Int, w: ByteArray): Int {
            val a = off[i]
            val b = off[i + 1]
            val n = minOf(b - a, w.size)
            for (j in 0 until n) {
                val diff = (bytes[a + j].toInt() and 0xFF) - (w[j].toInt() and 0xFF)
                if (diff != 0) return diff
            }
            return (b - a) - w.size
        }

        // banded log p(word), or null when absent (or not all Devanagari)
        fun logp(s: String): Double? {
            val w = ByteArray(s.length)
            for (i in s.indices) {
                val c = s[i].code
                if (c < 0x900 || c > 0x97F) return null
                w[i] = (c - 0x900 + 0x80).toByte()
            }
            for (g in 0 until groups.size / 2) {
                var lo = groups[2 * g]
                var hi = groups[2 * g + 1] - 1
                while (lo <= hi) {
                    val m = (lo + hi) ushr 1
                    val c = cmp(m, w)
                    if (c == 0) return centres[band[m].toInt() and 0xFF].toDouble()
                    if (c < 0) lo = m + 1 else hi = m - 1
                }
            }
            return null
        }
    }

    companion object {
        private const val CAP = 64
        private const val MAX_LEN = 40
        private const val CACHE_SIZE = 2000
        private const val PAD = 1
        private const val EOS = 2
        private const val UNK = 3
        private const val GELU_X = 6
        private const val GELU_N = 1024
        private const val SQRT1_2 = 0.7071067811865476

        // GELU (exact erf form) every 1/1024 on [-6, 6], linear in between
        private val GELU_T = FloatArray(2 * GELU_X * GELU_N + 2) {
            val x = it.toDouble() / GELU_N - GELU_X
            (0.5 * x * (1 + erf(x * SQRT1_2))).toFloat()
        }

        /**
         * Inputs may be gzip or raw; streams are read fully but not closed. [words] and [rerank]
         * are optional: without them candidates are ranked by model score alone.
         */
        fun load(model: InputStream, manifest: InputStream, words: InputStream?, rerank: InputStream?): LekhaniTransliterator {
            val man = MiniJson.parse(String(bytesOf(manifest), Charsets.UTF_8)) as Map<*, *>
            val raw = bytesOf(model)
            val rr = rerank?.let { MiniJson.parse(String(bytesOf(it), Charsets.UTF_8)) as Map<*, *> }
            val list = words?.let { WordList(bytesOf(it), (rr?.get("layout") as? String) ?: "v1") }
            return LekhaniTransliterator(man, raw, list, rr)
        }

        private fun bytesOf(input: InputStream): ByteArray {
            val b = input.readBytes()
            if (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) {
                return GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
            }
            return b
        }

        private fun u32(b: ByteArray, p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)

        private fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)

        private fun f16(hb: Int): Double {
            val s = if (hb and 0x8000 != 0) -1.0 else 1.0
            val e = (hb shr 10) and 31
            val m = hb and 1023
            if (e == 0) return s * m * Math.scalb(1.0, -24)
            if (e == 31) return if (m != 0) Double.NaN else s * Double.POSITIVE_INFINITY
            return s * (1 + m / 1024.0) * Math.scalb(1.0, e - 15)
        }

        // every tensor as f32; q4 tensors are 4-bit values (offset by 8) with one f16 scale per group of columns
        private fun loadWeights(man: Map<*, *>, raw: ByteArray): Map<String, FloatArray> {
            val g = (man["group"] as Double).toInt()
            val out = HashMap<String, FloatArray>()
            for (entry in man["tensors"] as List<*>) {
                val t = entry as Map<*, *>
                val shape = (t["shape"] as List<*>).map { (it as Double).toInt() }
                val n = shape.fold(1) { acc, v -> acc * v }
                val offset = (t["offset"] as Double).toInt()
                val w = FloatArray(n)
                if (t["kind"] == "q4") {
                    val cols = shape[1]
                    val per = cols / g
                    val so = offset + n / 2
                    for (i in 0 until n) {
                        val byte = raw[offset + (i shr 1)].toInt() and 0xFF
                        val qv = (if (i and 1 != 0) byte shr 4 else byte and 15) - 8
                        val r = i / cols
                        val c = i % cols
                        w[i] = (qv * f16(u16(raw, so + 2 * (r * per + c / g)))).toFloat()
                    }
                } else {
                    for (i in 0 until n) w[i] = f16(u16(raw, offset + 2 * i)).toFloat()
                }
                out[t["name"] as String] = w
            }
            return out
        }

        // with acc, the sum is rounded to f32 first, as adding a stored output would
        private fun put(y: FloatArray, k: Int, s: Double, acc: Boolean) {
            y[k] = if (acc) (y[k].toDouble() + s.toFloat()).toFloat() else s.toFloat()
        }

        private fun layerNorm(x: FloatArray, p: Norm, y: FloatArray, rows: Int) {
            val n = p.w.size
            for (r in 0 until rows) {
                val o = r * n
                var m = 0.0
                var v = 0.0
                for (i in 0 until n) m += x[o + i]
                m /= n
                for (i in 0 until n) {
                    val t = x[o + i] - m
                    v += t * t
                }
                val s = 1 / Math.sqrt(v / n + 1e-5)
                for (i in 0 until n) y[o + i] = ((x[o + i] - m) * s * p.w[i] + p.b[i]).toFloat()
            }
        }

        private fun gelu(xf: Float): Float {
            val x = xf.toDouble()
            if (x <= -GELU_X) return 0f
            if (x >= GELU_X) return xf
            val p = (x + GELU_X) * GELU_N
            val i = p.toInt()
            val a = GELU_T[i].toDouble()
            return (a + (p - i) * (GELU_T[i + 1] - a)).toFloat()
        }

        // |error| < 1.2e-7 (Numerical Recipes erfc, Chebyshev fit)
        private fun erf(x: Double): Double {
            val z = Math.abs(x)
            val t = 1 / (1 + 0.5 * z)
            val r = t * StrictMath.exp(
                -z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806 +
                    t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))),
            )
            return if (x >= 0) 1 - r else r - 1
        }

        // stable, ordered as JS sort((a, b) => key(b) - key(a))
        private inline fun descending(crossinline key: (Hyp) -> Double) = Comparator<Hyp> { p, q ->
            val diff = key(q) - key(p)
            if (diff > 0) 1 else if (diff < 0) -1 else 0
        }
    }
}

// Just enough JSON for model.json and rerank.json: objects, arrays, strings, numbers (as Double), literals
private object MiniJson {
    fun parse(s: String): Any? {
        val p = Parser(s)
        val v = p.value()
        p.ws()
        require(p.i == s.length) { "trailing data at ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            require(i < s.length) { "unexpected end" }
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return v
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && s[i] in "+-0123456789.eE") i++
            return s.substring(start, i).toDouble()
        }

        private fun str(): String {
            require(s[i] == '"') { "expected string at $i" }
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (s[i] == ']') {
                i++
                return out
            }
            while (true) {
                out.add(value())
                ws()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> error("expected , or ] at ${i - 1}")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') {
                i++
                return out
            }
            while (true) {
                ws()
                val k = str()
                ws()
                require(s[i++] == ':') { "expected : at ${i - 1}" }
                out[k] = value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> error("expected , or } at ${i - 1}")
                }
            }
        }
    }
}

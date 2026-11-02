// Verification harness for LekhaniTransliterator. Run via tools/translit/verify_lekhani.sh.
// Compares candidates(word, 4) with golden.tsv from the reference runner (make_golden.mjs): top-1 must
// match everywhere; a later difference passes only when it is a near-tie in the final scores.
import dev.zain.znkeyboard.translit.LekhaniTransliterator
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

const val TIE = 1e-6

fun main(args: Array<String>) {
    val dir = File(args[0])
    val golden = File(args[1]).readLines().filter { it.isNotEmpty() }.map { line ->
        val parts = line.split("\t")
        parts[0] to parts.getOrElse(1) { "" }.split(" ").filter { it.isNotEmpty() }
    }

    val t0 = System.nanoTime()
    val tr = LekhaniTransliterator.load(
        File(dir, "model.q4").inputStream(), File(dir, "model.json").inputStream(),
        File(dir, "words.bin").inputStream(), File(dir, "rerank.json").inputStream(),
    )
    val loadMs = (System.nanoTime() - t0) / 1e6

    val t1 = System.nanoTime()
    val got = golden.map { (word, _) -> tr.candidates(word, 4) }
    val coldMs = (System.nanoTime() - t1) / 1e6 / golden.size

    var top1 = 0
    var exact = 0
    var ties = 0
    var failures = 0
    golden.forEachIndexed { i, (word, want) ->
        val have = got[i]
        if (have.firstOrNull() == want.firstOrNull()) top1++
        if (have == want) {
            exact++
            return@forEachIndexed
        }
        val finals = HashMap<String, Double>()
        for ((text, final) in tr.scored(word)) if (text !in finals) finals[text] = final
        val at = (0 until maxOf(have.size, want.size)).first { have.getOrNull(it) != want.getOrNull(it) }
        val a = have.getOrNull(at)?.let { finals[it] }
        val b = want.getOrNull(at)?.let { finals[it] }
        val tie = at > 0 && a != null && b != null && abs(a - b) < TIE
        if (tie) ties++ else failures++
        println("${if (tie) "TIE " else "DIFF"} $word: want [${want.joinToString(" ")}] got [${have.joinToString(" ")}]" +
            " at $at (final ${a ?: "-"} vs ${b ?: "-"})")
    }

    // steady state: uncached, JIT warmed by the pass above
    val t2 = System.nanoTime()
    repeat(3) { golden.forEach { (word, _) -> tr.scored(word) } }
    val warmMs = (System.nanoTime() - t2) / 1e6 / (3 * golden.size)

    val n = golden.size
    println("top-1 agreement: $top1/$n (${"%.2f".format(100.0 * top1 / n)}%)")
    println("top-4 exact: $exact/$n, float ties: $ties, other differences: $failures")
    println("load: ${"%.0f".format(loadMs)} ms, per word: ${"%.2f".format(coldMs)} ms cold, ${"%.2f".format(warmMs)} ms warm")
    for (w in listOf("kaise", "ho", "yaar", "namaste", "pradhanmantri")) println("  $w -> ${tr.best(w)} ${tr.candidates(w)}")
    if (top1 != n || failures > 0) {
        println("FAIL")
        exitProcess(1)
    }
    println("PASS")
}

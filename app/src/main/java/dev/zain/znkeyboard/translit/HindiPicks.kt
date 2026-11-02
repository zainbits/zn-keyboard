package dev.zain.znkeyboard.translit

/**
 * The Devanagari spelling the user last picked for each romanised word; it ranks first from then on.
 * Picking the model's own top choice again drops the override, so the most recent pick always wins.
 * Not thread-safe: the keyboard uses it on the main thread only.
 */
class HindiPicks {
    private val picks = object : LinkedHashMap<String, String>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_PICKS
    }

    /** Bumped on every change, so callers can tell whether a save is needed. */
    var changes = 0L
        private set

    val size: Int get() = picks.size

    fun pickFor(latin: String): String? = picks[key(latin)]

    fun record(latin: String, picked: String, modelBest: String?) {
        val k = key(latin)
        if (k.isEmpty() || picked.isEmpty() || '\t' in picked || '\n' in picked) return
        val changed = if (picked == modelBest) picks.remove(k) != null else picks.put(k, picked) != picked
        if (changed) changes++
    }

    /** [modelCandidates] with the learned pick for [latin] moved (or added) to the front. */
    fun rank(latin: String, modelCandidates: List<String>): List<String> {
        val pick = pickFor(latin) ?: return modelCandidates
        return listOf(pick) + modelCandidates.filter { it != pick }
    }

    fun reset() {
        if (picks.isNotEmpty()) changes++
        picks.clear()
    }

    fun copyFrom(other: HindiPicks) {
        picks.clear()
        picks.putAll(other.picks)
        changes++
    }

    fun toMap(): Map<String, String> = LinkedHashMap(picks)

    /** One `latin<TAB>devanagari` line per pick, least recently used first. */
    fun encode(): ByteArray = buildString {
        picks.forEach { (latin, picked) -> append(latin).append('\t').append(picked).append('\n') }
    }.toByteArray(Charsets.UTF_8)

    companion object {
        const val MAX_PICKS = 5_000
        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f

        /** The lowercase a–z letters of [latin]: the only part the model reads. */
        fun key(latin: String): String = buildString(latin.length) {
            latin.forEach { ch ->
                val lower = ch.lowercaseChar()
                if (lower in 'a'..'z') append(lower)
            }
        }

        fun decode(bytes: ByteArray): HindiPicks? = runCatching {
            HindiPicks().also { result ->
                String(bytes, Charsets.UTF_8).lineSequence().forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab > 0) result.record(line.substring(0, tab), line.substring(tab + 1), modelBest = null)
                }
            }
        }.getOrNull()

        fun fromMap(map: Map<String, String>): HindiPicks = HindiPicks().also { result ->
            map.forEach { (latin, picked) -> result.record(latin, picked, modelBest = null) }
        }
    }
}

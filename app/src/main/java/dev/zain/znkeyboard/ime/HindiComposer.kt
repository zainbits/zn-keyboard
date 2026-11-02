package dev.zain.znkeyboard.ime

import android.view.inputmethod.InputConnection
import dev.zain.znkeyboard.translit.HindiPicks
import dev.zain.znkeyboard.translit.HindiTransliterationEngine

/**
 * Hindi mode's editing state. The Latin word being typed stays in the field as composing text;
 * finishing it (space, punctuation, Enter) swaps in the best Devanagari spelling. Right after that,
 * the strip offers the runner-up and Backspace brings the Latin word back.
 *
 * Main-thread only. The caller wraps multi-step edits in a batch edit and counts its own edits.
 */
internal class HindiComposer(
    private val engine: HindiTransliterationEngine,
    private val host: Host,
) {
    interface Host {
        fun composerInputConnection(): InputConnection?
        fun onComposerEdit()
        fun onHindiStripChanged()
    }

    private val composing = StringBuilder()
    private var candidates: List<String> = emptyList()
    // The model key (lowercase a–z) that [candidates] belong to; taps on another word's list are ignored.
    private var candidatesKey = ""
    private var highlighted: String? = null
    private var lastConversion: Conversion? = null
    // Words committed in Latin because the model hadn't answered yet, oldest first.
    private val pending = ArrayList<Conversion>()
    // After Backspace undid a conversion, finishing the word keeps it in Latin (Gboard's convention).
    private var keepLatin = false

    val isComposing: Boolean get() = composing.isNotEmpty()

    /** Strip candidates, best (or the committed one) first, with whether each is highlighted. */
    fun stripCandidates(limit: Int): List<Pair<String, Boolean>> {
        if (candidates.isEmpty()) return emptyList()
        val selected = highlighted
        val ordered = if (selected == null) candidates else listOf(selected) + candidates.filter { it != selected }
        return ordered.take(limit).map { it to (it == selected) }
    }

    fun onLetter(ic: InputConnection, letter: String) {
        ic.beginBatchEdit()
        try {
            if (composing.isEmpty()) {
                lastConversion = null
                keepLatin = false
                absorbLatinBeforeCursor(ic)
            }
            composing.append(letter)
            ic.setComposingText(composing, 1)
        } finally {
            ic.endBatchEdit()
        }
        refreshCandidates()
    }

    /** Finishes the word before [separator] is typed: Devanagari when the model has answered, else later. */
    fun commitForSeparator(ic: InputConnection, separator: String, allowUndo: Boolean = true) {
        val latin = composing.toString()
        composing.clear()
        if (keepLatin) {
            keepLatin = false
            ic.finishComposingText()
            appendToPending(latin + separator)
            clearStrip()
            return
        }
        val best = engine.cached(latin)?.firstOrNull()
        if (best != null) {
            ic.commitText(best, 1)
            appendToPending(best + separator)
            if (allowUndo) {
                lastConversion = Conversion(latin, best, separator)
                showCandidates(HindiPicks.key(latin), engine.cached(latin).orEmpty(), best)
            } else {
                clearStrip()
            }
            return
        }
        ic.finishComposingText()
        appendToPending(latin + separator)
        clearStrip()
        if (!allowUndo || !engine.isReady) return
        // The model hasn't answered yet: keep the Latin word and swap it in once it does.
        val waiting = Conversion(latin, latin, separator)
        pending += waiting
        while (pending.size > MAX_PENDING) pending.removeAt(0)
        engine.request(latin, urgent = true) { result -> applyPending(waiting, result) }
    }

    /** Before non-letter text when no word is being typed: smart punctuation after a picked candidate. */
    fun beforeOtherText(ic: InputConnection, value: String) {
        val conversion = lastConversion
        lastConversion = null
        if (conversion != null && conversion.autoSpace && value.length == 1 && value[0] in SPACE_EATING_PUNCTUATION) {
            val tail = conversion.committed + conversion.separator
            if (ic.getTextBeforeCursor(tail.length, 0)?.toString() == tail) {
                ic.deleteSurroundingText(1, 0)
                pending.forEach { if (it.after.endsWith(" ")) it.after = it.after.dropLast(1) }
            }
        }
        appendToPending(value)
        clearStrip()
    }

    /** True when Backspace was handled: shortening the word, or undoing the last conversion. */
    fun onBackspace(ic: InputConnection): Boolean {
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.lastIndex)
            if (composing.isEmpty()) {
                ic.commitText("", 1)
                keepLatin = false
                clearStrip()
            } else {
                ic.setComposingText(composing, 1)
                refreshCandidates()
            }
            return true
        }
        pending.clear()
        val conversion = lastConversion ?: return false
        lastConversion = null
        if (!ic.getSelectedText(0).isNullOrEmpty()) return false
        val tail = conversion.committed + conversion.separator
        if (ic.getTextBeforeCursor(tail.length, 0)?.toString() != tail) {
            clearStrip()
            return false
        }
        ic.beginBatchEdit()
        try {
            ic.deleteSurroundingText(tail.length, 0)
            composing.append(conversion.latin)
            ic.setComposingText(composing, 1)
        } finally {
            ic.endBatchEdit()
        }
        keepLatin = true
        refreshCandidates()
        return true
    }

    /** A strip candidate was tapped: it replaces the word being typed, or the word just converted. */
    fun onCandidateSelected(ic: InputConnection, text: String): Boolean {
        if (composing.isNotEmpty()) {
            val latin = composing.toString()
            // The strip may still show the previous word's list while this word's is computed.
            if (HindiPicks.key(latin) != candidatesKey || text !in candidates) return false
            composing.clear()
            keepLatin = false
            host.onComposerEdit()
            ic.beginBatchEdit()
            try {
                ic.commitText(text, 1)
                ic.commitText(" ", 1)
            } finally {
                ic.endBatchEdit()
            }
            appendToPending("$text ")
            engine.recordPick(latin, text)
            lastConversion = Conversion(latin, text, " ", autoSpace = true)
            showCandidates(candidatesKey, engine.cached(latin) ?: candidates, text)
            return true
        }
        val conversion = lastConversion ?: return false
        if (text == conversion.committed || HindiPicks.key(conversion.latin) != candidatesKey) return false
        val tail = conversion.committed + conversion.separator
        if (ic.getTextBeforeCursor(tail.length, 0)?.toString() != tail) {
            lastConversion = null
            clearStrip()
            return false
        }
        // An edit that leaves the cursor where it was gets no selection update, so isn't counted.
        if (text.length != conversion.committed.length) host.onComposerEdit()
        ic.beginBatchEdit()
        try {
            ic.deleteSurroundingText(tail.length, 0)
            ic.commitText(text + conversion.separator, 1)
        } finally {
            ic.endBatchEdit()
        }
        val newTail = text + conversion.separator
        pending.forEach { if (it.after.endsWith(tail)) it.after = it.after.dropLast(tail.length) + newTail }
        engine.recordPick(conversion.latin, text)
        conversion.committed = text
        showCandidates(candidatesKey, engine.cached(conversion.latin) ?: candidates, text)
        return true
    }

    /** Leaves the word being typed in Latin and forgets every pending conversion. */
    fun finish(ic: InputConnection?) {
        if (composing.isNotEmpty()) ic?.finishComposingText()
        reset()
    }

    /** Forgets all state without touching the field (it changed or went away underneath us). */
    fun reset() {
        composing.clear()
        lastConversion = null
        pending.clear()
        keepLatin = false
        clearStrip()
    }

    private fun absorbLatinBeforeCursor(ic: InputConnection) {
        val after = ic.getTextAfterCursor(1, 0)
        if (!after.isNullOrEmpty() && isWordChar(after[0])) return
        val before = ic.getTextBeforeCursor(MAX_ABSORBED_CHARS + 1, 0)?.toString() ?: return
        var start = before.length
        while (start > 0 && isAsciiLetter(before[start - 1])) start--
        val run = before.length - start
        if (run == 0 || run > MAX_ABSORBED_CHARS) return
        if (start > 0 && isWordChar(before[start - 1])) return
        ic.deleteSurroundingText(run, 0)
        composing.append(before, start, before.length)
    }

    private fun refreshCandidates() {
        val latin = composing.toString()
        val key = HindiPicks.key(latin)
        val cached = engine.cached(latin)
        if (cached != null) {
            showCandidates(key, cached, if (keepLatin) null else cached.firstOrNull())
            return
        }
        // Keep the previous list on screen (unhighlighted, untappable) until this word's arrives, to avoid flicker.
        showCandidates(candidatesKey, candidates, null)
        engine.request(latin) { result ->
            if (composing.toString() == latin) showCandidates(key, result, if (keepLatin) null else result.firstOrNull())
        }
    }

    /** Text committed after the words still waiting for the model. */
    private fun appendToPending(text: String) {
        pending.forEach { it.after += text }
    }

    /**
     * Swaps a waiting word for its Devanagari spelling, provided the field still ends with exactly
     * that word plus everything typed since; otherwise the word stays in Latin.
     */
    private fun applyPending(waiting: Conversion, result: List<String>) {
        val index = pending.indexOfFirst { it === waiting }
        if (index < 0) return
        pending.removeAt(index)
        val best = result.firstOrNull() ?: return
        val ic = host.composerInputConnection() ?: return
        val typedAhead = composing.toString()
        val tail = waiting.latin + waiting.after + typedAhead
        val before = ic.getTextBeforeCursor(tail.length + 1, 0)?.toString() ?: return
        if (!before.endsWith(tail)) return
        if (before.length > tail.length && isWordChar(before[before.length - tail.length - 1])) return
        if (best.length != waiting.latin.length) host.onComposerEdit()
        ic.beginBatchEdit()
        try {
            if (typedAhead.isNotEmpty()) ic.finishComposingText()
            ic.deleteSurroundingText(tail.length, 0)
            ic.commitText(best + waiting.after, 1)
            if (typedAhead.isNotEmpty()) ic.setComposingText(typedAhead, 1)
        } finally {
            ic.endBatchEdit()
        }
        // Older waiting words have this word in the text after them.
        val oldText = waiting.latin + waiting.after
        val newText = best + waiting.after
        for (i in 0 until index) {
            val earlier = pending[i]
            if (earlier.after.endsWith(oldText)) earlier.after = earlier.after.dropLast(oldText.length) + newText
        }
        if (typedAhead.isEmpty() && index == pending.size && lastConversion == null) {
            lastConversion = Conversion(waiting.latin, best, waiting.after)
            showCandidates(HindiPicks.key(waiting.latin), result, best)
        }
    }

    private fun showCandidates(key: String, list: List<String>, selected: String?) {
        if (candidatesKey == key && candidates == list && highlighted == selected) return
        candidatesKey = key
        candidates = list
        highlighted = selected
        host.onHindiStripChanged()
    }

    private fun clearStrip() = showCandidates("", emptyList(), null)

    private class Conversion(
        val latin: String,
        var committed: String,
        val separator: String,
        val autoSpace: Boolean = false,
    ) {
        /** For a waiting word: everything committed after it, starting with its separator. */
        var after: String = separator
    }

    companion object {
        private const val MAX_ABSORBED_CHARS = 40
        private const val MAX_PENDING = 8
        private const val SPACE_EATING_PUNCTUATION = ".,?!:;"

        fun isAsciiLetter(ch: Char): Boolean = ch in 'a'..'z' || ch in 'A'..'Z'

        /** Letters, digits and combining marks: Devanagari vowel signs are marks, not letters. */
        fun isWordChar(ch: Char): Boolean {
            if (ch.isLetterOrDigit()) return true
            val type = Character.getType(ch)
            return type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
        }
    }
}

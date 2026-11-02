package dev.zain.znkeyboard.translit

/**
 * Rejects Devanagari the model can emit but no one writes, such as a doubled vowel sign
 * ("कैसेे") or a nukta after a vowel sign ("हो़"). They render like the real word, so they would
 * show up as duplicate suggestions.
 */
object DevanagariText {
    fun isWellFormed(text: String): Boolean {
        var previous = Kind.Start
        for (char in text) {
            val kind = kindOf(char)
            val allowed = when (kind) {
                Kind.Nukta -> previous == Kind.Consonant
                Kind.VowelSign, Kind.Virama -> previous == Kind.Consonant || previous == Kind.ConsonantWithNukta
                Kind.Nasal -> previous == Kind.Consonant || previous == Kind.ConsonantWithNukta ||
                    previous == Kind.VowelSign || previous == Kind.Vowel
                else -> true
            }
            if (!allowed) return false
            previous = if (kind == Kind.Nukta) Kind.ConsonantWithNukta else kind
        }
        return true
    }

    private enum class Kind { Start, Consonant, ConsonantWithNukta, Vowel, VowelSign, Nukta, Virama, Nasal, Other }

    private fun kindOf(char: Char): Kind = when (char) {
        in '\u0915'..'\u0939', in '\u0958'..'\u095F', in '\u0978'..'\u097F' -> Kind.Consonant
        in '\u0904'..'\u0914', in '\u0960'..'\u0961', in '\u0972'..'\u0977' -> Kind.Vowel
        '\u093C' -> Kind.Nukta
        '\u094D' -> Kind.Virama
        in '\u0900'..'\u0903' -> Kind.Nasal
        in '\u093A'..'\u093B', in '\u093E'..'\u094C', in '\u094E'..'\u094F', in '\u0955'..'\u0957', in '\u0962'..'\u0963' -> Kind.VowelSign
        else -> Kind.Other
    }
}

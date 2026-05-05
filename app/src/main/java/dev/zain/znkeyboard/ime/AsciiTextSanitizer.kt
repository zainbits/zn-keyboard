package dev.zain.znkeyboard.ime

import java.text.Normalizer

object AsciiTextSanitizer {
    private val replacements = mapOf(
        0x201C to "\"",
        0x201D to "\"",
        0x2018 to "'",
        0x2019 to "'",
        0x201A to "'",
        0x201E to "\"",
        0x2039 to "<",
        0x203A to ">",
        0x00AB to "<<",
        0x00BB to ">>",
        0x2014 to "-",
        0x2013 to "-",
        0x2012 to "-",
        0x2015 to "-",
        0x2010 to "-",
        0x2011 to "-",
        0x00AD to "",
        0x00A0 to " ",
        0x2002 to " ",
        0x2003 to " ",
        0x2009 to " ",
        0x200A to " ",
        0x202F to " ",
        0x205F to " ",
        0x200B to "",
        0x200C to "",
        0x200D to "",
        0xFEFF to "",
        0x200E to "",
        0x200F to "",
        0x2060 to "",
        0x2061 to "",
        0x2062 to "",
        0x2063 to "",
        0x2064 to "",
        0x2026 to "...",
        0x2022 to "*",
        0x2023 to ">",
        0x2043 to "-",
        0x00B7 to ".",
        0x2027 to ".",
        0x2032 to "'",
        0x2033 to "\"",
        0x00D7 to "x",
        0x2212 to "-",
        0x2248 to "~=",
        0x2260 to "!=",
        0x2264 to "<=",
        0x2265 to ">=",
        0x2192 to "->",
        0x2190 to "<-",
        0x00A9 to "(c)",
        0x00AE to "(R)",
        0x2122 to "(TM)",
        0x3010 to "[",
        0x3011 to "]",
        0xFF5B to "{",
        0xFF5D to "}",
    )

    fun toAscii(text: String): String {
        return buildString(text.length) {
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                val replacement = replacements[codePoint]
                when {
                    replacement != null -> append(replacement)
                    codePoint <= ASCII_MAX_CODE_POINT -> appendCodePoint(codePoint)
                    else -> appendNormalizedAscii(codePoint)
                }
                index += Character.charCount(codePoint)
            }
        }
    }

    private fun StringBuilder.appendNormalizedAscii(codePoint: Int) {
        val normalized = Normalizer.normalize(
            String(Character.toChars(codePoint)),
            Normalizer.Form.NFKD,
        )
        for (char in normalized) {
            if (char.code <= ASCII_MAX_CODE_POINT) {
                append(char)
            }
        }
    }

    private const val ASCII_MAX_CODE_POINT = 0x7F
}

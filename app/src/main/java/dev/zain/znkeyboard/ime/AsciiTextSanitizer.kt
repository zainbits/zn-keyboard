package dev.zain.znkeyboard.ime

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
                if (replacement != null) {
                    append(replacement)
                } else {
                    appendCodePoint(codePoint)
                }
                index += Character.charCount(codePoint)
            }
        }
    }
}

package com.wbooks.parser.highlight

/**
 * Lightweight regex-based syntax colouring for [com.wbooks.parser.model.Block.Code] blocks
 * surfaced by [com.wbooks.parser.HtmlParser]. Designed for watch: tiny ruleset, no PEG,
 * no per-language grammar files.
 *
 * Output is a list of [Token]s in source order; the renderer maps [TokenKind] to colours
 * pulled from the active theme so highlighting respects the user's text-colour choice.
 */
object SyntaxHighlighter {

    enum class TokenKind { KEYWORD, STRING, NUMBER, COMMENT, IDENTIFIER, PUNCT, PLAIN }

    data class Token(val text: String, val kind: TokenKind)

    private val GENERIC_KEYWORDS = setOf(
        "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue",
        "return", "void", "int", "long", "short", "float", "double", "char", "byte", "boolean",
        "class", "interface", "enum", "struct", "trait", "object", "fun", "def", "function",
        "val", "var", "let", "const", "static", "final", "abstract", "public", "private",
        "protected", "internal", "package", "import", "from", "as", "new", "delete", "this",
        "self", "super", "null", "nil", "None", "true", "false", "try", "catch", "finally",
        "throw", "throws", "raise", "with", "lambda", "async", "await", "yield",
    )

    fun highlight(source: String, language: String? = null): List<Token> {
        // language is currently unused (single ruleset) — kept for the API so we can
        // add language-specific keyword sets later without touching callers.
        val out = mutableListOf<Token>()
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            when {
                c == '/' && i + 1 < n && source[i + 1] == '/' -> {
                    val end = source.indexOf('\n', i).let { if (it == -1) n else it }
                    out += Token(source.substring(i, end), TokenKind.COMMENT)
                    i = end
                }
                c == '/' && i + 1 < n && source[i + 1] == '*' -> {
                    val end = source.indexOf("*/", i + 2).let { if (it == -1) n else it + 2 }
                    out += Token(source.substring(i, end), TokenKind.COMMENT)
                    i = end
                }
                c == '#' -> {
                    val end = source.indexOf('\n', i).let { if (it == -1) n else it }
                    out += Token(source.substring(i, end), TokenKind.COMMENT)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    val end = findStringEnd(source, i, c)
                    out += Token(source.substring(i, end), TokenKind.STRING)
                    i = end
                }
                c.isDigit() -> {
                    var j = i + 1
                    while (j < n && (source[j].isLetterOrDigit() || source[j] == '.' || source[j] == '_')) j++
                    out += Token(source.substring(i, j), TokenKind.NUMBER)
                    i = j
                }
                c.isLetter() || c == '_' -> {
                    var j = i + 1
                    while (j < n && (source[j].isLetterOrDigit() || source[j] == '_')) j++
                    val word = source.substring(i, j)
                    val kind = if (word in GENERIC_KEYWORDS) TokenKind.KEYWORD else TokenKind.IDENTIFIER
                    out += Token(word, kind)
                    i = j
                }
                c.isWhitespace() -> {
                    var j = i + 1
                    while (j < n && source[j].isWhitespace()) j++
                    out += Token(source.substring(i, j), TokenKind.PLAIN)
                    i = j
                }
                else -> {
                    out += Token(c.toString(), TokenKind.PUNCT)
                    i++
                }
            }
        }
        return out
    }

    private fun findStringEnd(s: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                '\n' -> return i  // unterminated; bail
                else -> i++
            }
        }
        return s.length
    }
}

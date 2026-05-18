package com.wbooks.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.wbooks.data.settings.FontChoice
import com.wbooks.data.settings.ReaderSettings
import com.wbooks.parser.highlight.SyntaxHighlighter
import com.wbooks.parser.model.Block
import com.wbooks.parser.model.Run

/**
 * Reusable block rendering used by [NormalMode]. Kept here so [SentenceMode]
 * and [SpeedReadMode] can pull single-block rendering bits if useful later.
 */

@Composable
fun BlockView(block: Block, settings: ReaderSettings) {
    val baseColor = Color(settings.textColorArgb)
    val baseSize = settings.textSizeSp.sp
    val family = settings.font.toFontFamily()

    when (block) {
        is Block.Heading -> {
            val bump = (6 - block.level * 2).coerceAtLeast(0)
            Text(
                text = block.text,
                color = baseColor,
                fontFamily = family,
                fontWeight = FontWeight.Bold,
                fontSize = (settings.textSizeSp + bump).sp,
            )
        }
        is Block.Paragraph -> {
            val annotated = remember(block) { block.runs.toAnnotatedString() }
            Text(
                text = annotated,
                color = baseColor,
                fontFamily = family,
                fontSize = baseSize,
            )
        }
        Block.Divider -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(1.dp)
                    .background(baseColor.copy(alpha = 0.4f))
            )
        }
        is Block.Code -> {
            val annotated = remember(block) { highlightCode(block) }
            Text(
                text = annotated,
                color = baseColor,
                fontFamily = FontFamily.Monospace,
                fontSize = (settings.textSizeSp - 2).coerceAtLeast(10).sp,
            )
        }
    }
}

private fun FontChoice.toFontFamily(): FontFamily = when (this) {
    FontChoice.SERIF -> FontFamily.Serif
    FontChoice.SANS -> FontFamily.SansSerif
    FontChoice.MONO -> FontFamily.Monospace
}

private fun List<Run>.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    for (run in this@toAnnotatedString) {
        val style = SpanStyle(
            fontWeight = if (run.style.bold) FontWeight.Bold else null,
            fontStyle = if (run.style.italic) FontStyle.Italic else null,
            textDecoration = if (run.style.underline) TextDecoration.Underline else null,
            color = run.style.color?.let { Color(it) } ?: Color.Unspecified,
        )
        withStyle(style) { append(run.text) }
    }
}

/** Generic-language syntax colouring for a code block; uses theme-friendly hues. */
private fun highlightCode(block: Block.Code): AnnotatedString = buildAnnotatedString {
    for (tok in SyntaxHighlighter.highlight(block.text, block.language)) {
        val color = when (tok.kind) {
            SyntaxHighlighter.TokenKind.KEYWORD -> Color(0xFFB392F0)
            SyntaxHighlighter.TokenKind.STRING -> Color(0xFF9ECBFF)
            SyntaxHighlighter.TokenKind.NUMBER -> Color(0xFFF0883E)
            SyntaxHighlighter.TokenKind.COMMENT -> Color(0xFF8B949E)
            SyntaxHighlighter.TokenKind.IDENTIFIER,
            SyntaxHighlighter.TokenKind.PUNCT,
            SyntaxHighlighter.TokenKind.PLAIN -> Color.Unspecified
        }
        withStyle(SpanStyle(color = color)) { append(tok.text) }
    }
}

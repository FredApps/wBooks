package com.fredapp.wbooks.ui.reader

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.fredapp.wbooks.data.settings.ReaderSettings
import com.fredapp.wbooks.parser.highlight.SyntaxHighlighter
import com.fredapp.wbooks.parser.model.Block
import com.fredapp.wbooks.parser.model.Run
import com.fredapp.wbooks.ui.theme.toFontFamily
import kotlin.math.min
import kotlin.math.sqrt

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
        is Block.Image -> ImageBlockView(block, baseColor, family, baseSize)
    }
}

/**
 * Render a [Block.Image] within the watch's circular safe area.
 *
 * On a round screen the largest axis-aligned rectangle that stays clear of
 * the bezel is the inscribed square - side = diameter / sqrt(2). We cap the
 * image to that side (or to the screen min-axis on a square watch) and use
 * [ContentScale.Fit] so portrait, landscape, and square sources all stay
 * inside the visible area. Letterboxing is handled via centring in a Box.
 */
@Composable
private fun ImageBlockView(
    block: Block.Image,
    baseColor: Color,
    family: FontFamily,
    baseSize: TextUnit,
) {
    val config = LocalConfiguration.current
    val isRound = config.isScreenRound
    val minAxis = min(config.screenWidthDp, config.screenHeightDp).dp
    val safeSide = if (isRound) (minAxis * (1f / sqrt(2f))) else minAxis
    val maxBitmapPx = with(LocalDensity.current) { safeSide.roundToPx() }.coerceAtLeast(1)
    val bitmap = remember(block, maxBitmapPx) {
        decodeSampledBitmap(block.bytes, maxBitmapPx)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = block.alt.ifBlank { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier.sizeIn(maxWidth = safeSide, maxHeight = safeSide),
            )
        } else {
            // Decode failed (unsupported codec, corrupt bytes). Show the alt
            // text so the reader at least knows there was an image here.
            val fallback = block.alt.ifBlank { "[image]" }
            Text(
                text = fallback,
                color = baseColor.copy(alpha = 0.7f),
                fontFamily = family,
                fontStyle = FontStyle.Italic,
                fontSize = baseSize,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Decode at roughly the largest size the watch can display. EPUB cover art can
 * easily be thousands of pixels wide; decoding those full-size bitmaps for a
 * 300-450 px display is wasted heap and a classic path to OOM on Wear OS.
 */
private fun decodeSampledBitmap(bytes: ByteArray, maxDisplayPx: Int): android.graphics.Bitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= maxDisplayPx) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

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

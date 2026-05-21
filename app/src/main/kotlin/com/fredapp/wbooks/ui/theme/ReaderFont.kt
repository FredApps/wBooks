package com.fredapp.wbooks.ui.theme

import androidx.compose.ui.text.font.FontFamily
import com.fredapp.wbooks.data.settings.FontChoice

fun FontChoice.toFontFamily(): FontFamily = when (this) {
    FontChoice.DEFAULT -> FontFamily.Default
    FontChoice.SERIF -> FontFamily.Serif
    FontChoice.SANS -> FontFamily.SansSerif
    FontChoice.MONO -> FontFamily.Monospace
    FontChoice.CURSIVE -> FontFamily.Cursive
}

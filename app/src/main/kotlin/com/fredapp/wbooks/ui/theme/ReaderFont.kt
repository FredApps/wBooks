package com.fredapp.wbooks.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.fredapp.wbooks.R
import com.fredapp.wbooks.data.settings.FontChoice

fun FontChoice.toFontFamily(): FontFamily = when (this) {
    FontChoice.DEFAULT -> FontFamily.Default
    FontChoice.SERIF -> FontFamily.Serif
    FontChoice.SANS -> FontFamily.SansSerif
    FontChoice.MONO -> FontFamily.Monospace
    FontChoice.CURSIVE -> FontFamily.Cursive
    FontChoice.INTER_LIGHT -> FontFamily(Font(R.font.inter_light, FontWeight.Light))
    FontChoice.INTER_MEDIUM -> FontFamily(Font(R.font.inter_medium, FontWeight.Medium))
}

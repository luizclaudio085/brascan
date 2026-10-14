package com.brastoy.brascan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val bg = Color(0xFF05080F)
    val card = Color(0xFF0E1728)
    val cardHi = Color(0xFF15223A)
    val stroke = Color(0x2A7FD6FF)
    val accent = Color(0xFF7FD6FF)
    val pink = Color(0xFFFF5C8A)
    val muted = Color(0xFF8DA2C0)
    val x = Color(0xFFFF5C6C)
    val y = Color(0xFF3DDC97)
    val z = Color(0xFF4DA3FF)
    val tiles = listOf(0xFFE53950, 0xFF2F8CFF, 0xFFFFC531, 0xFF2EC27E, 0xFF9B5DE5, 0xFFFF7A2F).map { it.toInt() }
}

@Composable
fun BraScanTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Palette.accent, onPrimary = Color(0xFF00243A), secondary = Palette.pink,
        background = Palette.bg, surface = Palette.card, surfaceVariant = Palette.cardHi,
        surfaceContainer = Palette.card, surfaceContainerHigh = Palette.cardHi, secondaryContainer = Color(0xFF1C3352),
        onSurface = Color(0xFFE8F1FF), onSurfaceVariant = Palette.muted, outline = Color(0xFF2B3D5C),
    ),
    content = content,
)

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Column(
    modifier
        .clip(RoundedCornerShape(22.dp))
        .background(Brush.verticalGradient(listOf(Palette.cardHi, Palette.card)))
        .border(1.dp, Palette.stroke, RoundedCornerShape(22.dp))
        .padding(16.dp),
    content = content,
)

@Composable
fun Label(text: String) = Text(text.uppercase(), color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)

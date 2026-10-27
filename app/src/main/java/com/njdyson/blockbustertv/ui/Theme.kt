package com.njdyson.blockbustertv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val TicketYellow = Color(0xFFFFCC00)
val StoreBlue = Color(0xFF102E8C)
val NightNavy = Color(0xFF0A143C)
val ShelfGrey = Color(0xFF1C2550)
val Good = Color(0xFF4CAF50)
val Partial = Color(0xFFFFA000)
val Bad = Color(0xFFE53935)

@Composable
fun BlockbusterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TicketYellow,
            onPrimary = NightNavy,
            secondary = StoreBlue,
            background = NightNavy,
            surface = ShelfGrey,
            onSurface = Color.White,
            onBackground = Color.White,
        ),
        content = content,
    )
}

package com.flex.elefin.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A digital clock component that displays the current time and updates every second.
 * Styled to match the refined navigation bars.
 *
 * NOTE (android5 fix): uses java.util.Date/SimpleDateFormat instead of java.time.*
 * because java.time requires API 26+ and would crash on Android 5.0 (core library
 * desugaring is not enabled).
 */
@Composable
fun DigitalClock(
    modifier: Modifier = Modifier,
    use24HourFormat: Boolean = false
) {
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            delay(1000)
        }
    }

    val pattern = if (use24HourFormat) "HH:mm" else "h:mm a"
    val formatter = remember(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }
    val timeString = formatter.format(Date(currentTime))
    
    // Match the 30% reduction scaling used in navigation tabs (1.17 * 0.7 = 0.819f)
    val scaledFontSize = MaterialTheme.typography.labelLarge.fontSize * 0.82f

    Text(
        text = timeString,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.Bold,
            fontSize = scaledFontSize
        ),
        color = Color.White,
        modifier = modifier.padding(end = 38.dp) // Match the 38dp start padding for balance
    )
}

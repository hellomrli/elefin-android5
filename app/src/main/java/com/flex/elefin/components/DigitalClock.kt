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
import kotlinx.coroutines.isActive
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.TimeZone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minute clock; timers and time-change listeners run only while the screen is visible. */
@Composable
fun DigitalClock(
    modifier: Modifier = Modifier,
    use24HourFormat: Boolean = false
) {
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var timeZoneId by remember { mutableStateOf(TimeZone.getDefault().id) }
    LaunchedEffect(lifecycleOwner, context) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    timeZoneId = TimeZone.getDefault().id
                    currentTime = System.currentTimeMillis()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                while (isActive) {
                    currentTime = System.currentTimeMillis()
                    timeZoneId = TimeZone.getDefault().id
                    delay(60_000 - currentTime % 60_000)
                }
            } finally {
                context.unregisterReceiver(receiver)
            }
        }
    }

    val pattern = if (use24HourFormat) "HH:mm" else "h:mm a"
    val formatter = remember(pattern, timeZoneId, Locale.getDefault()) {
        SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone(timeZoneId) }
    }
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

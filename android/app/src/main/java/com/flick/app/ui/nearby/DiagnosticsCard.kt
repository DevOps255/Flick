package com.flick.app.ui.nearby

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flick.app.data.nearby.Diagnostics
import kotlinx.coroutines.flow.StateFlow

/**
 * What Bluetooth and the pairing are doing. When two phones do not see each other or do not pair,
 * this card says which step stops, so a photo of it on both phones is enough to find the cause.
 */
@Composable
fun DiagnosticsCard(source: StateFlow<Diagnostics>, modifier: Modifier = Modifier) {
    val d by source.collectAsStateWithLifecycle()
    Column(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background, RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        Line("Bluetooth", d.bluetooth)
        Line("Advertising", if (d.advertising) "yes" else "no")
        Line("Scanning", if (d.scanning) "yes" else "no")
        Line("Frames heard from other phones", d.framesHeard.toString())
        Line("Signal of the closest phone", d.strongestRssi?.let { "$it dBm" } ?: "none")
        Line("Gestures detected here", d.gestures.toString())
        Line("Last time to be ready", d.lastReadyMs?.let { "${it / 1000.0} s" } ?: "none yet")
        Line("Network address", if (d.hasAddress) "yes" else "none, a Wi-Fi Direct group will be formed")
        (d.hint ?: advice(d))?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
}

/** The most likely cause, in one sentence, or null when nothing looks wrong. */
private fun advice(d: Diagnostics): String? = when {
    d.bluetooth == "off" -> "Turn Bluetooth on."
    d.bluetooth == "permission missing" -> "Allow the Bluetooth permission for Flick in the phone settings."
    d.bluetooth.contains("failed") -> "Bluetooth failed to start (${d.bluetooth}). Turn Bluetooth off and on, then reopen Flick."
    !d.advertising -> "This phone is not advertising yet. Wait a second, or turn Bluetooth off and on."
    d.framesHeard == 0 -> "This phone has heard no other phone. On Android 11 and older, location must also be on. Check that Flick is open on the other phone."
    d.gestures == 0 -> "Tilt the phone, as when bringing two iPhones together, to pair."
    else -> null
}

package com.flick.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flick.app.ui.theme.Accent
import androidx.compose.ui.graphics.Color

/**
 * Two small round buttons for a long list: one jumps to the top, the other to the bottom.
 * Each shows only when the list can move in that direction.
 */
@Composable
fun ScrollJump(canGoUp: Boolean, canGoDown: Boolean, onUp: () -> Unit, onDown: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(canGoUp, enter = fadeIn(), exit = fadeOut()) {
            SmallFloatingActionButton(onClick = onUp, containerColor = Accent, contentColor = Color.White) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Back to the top")
            }
        }
        AnimatedVisibility(canGoDown, enter = fadeIn(), exit = fadeOut()) {
            SmallFloatingActionButton(onClick = onDown, containerColor = Accent, contentColor = Color.White) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Down to the end")
            }
        }
    }
}

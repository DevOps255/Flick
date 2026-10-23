package com.flick.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flick.app.ui.theme.AvatarPalette
import com.flick.app.ui.theme.pressScale

@Composable
fun FlickButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    container: Color,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        interactionSource = source,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = Color.White),
        modifier = modifier.height(52.dp).pressScale(source),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

/** Round avatar showing initials. Photos are not collected, so initials are the identity. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val initials = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(AvatarPalette[(name.hashCode() and 0x7fffffff) % AvatarPalette.size]),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.36f).sp)
    }
}

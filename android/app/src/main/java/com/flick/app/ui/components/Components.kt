package com.flick.app.ui.components

import androidx.compose.foundation.Image
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.flick.app.ui.theme.AvatarPalette
import com.flick.app.ui.theme.pressScale
import java.io.File

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

/**
 * Round avatar. It shows [photoPath] when there is one, and the initials of [name] otherwise.
 * Only the local user has a photo: photos are never sent to other phones.
 */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp, photoPath: String? = null) {
    val initials = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(AvatarPalette[(name.hashCode() and 0x7fffffff) % AvatarPalette.size]),
        contentAlignment = Alignment.Center,
    ) {
        if (photoPath != null) {
            AsyncImage(model = File(photoPath), contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(initials, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.36f).sp)
        }
    }
}

/**
 * An illustration that the designer supplies as a PNG in `res/drawable-nodpi`, named [resourceName].
 * Until the file exists, [fallback] is drawn, so the screen works before the artwork is dropped in.
 * See `docs/ICONS.md` for the list of names.
 */
@Composable
fun IsoIcon(resourceName: String, fallback: ImageVector, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    val context = LocalContext.current
    val id = remember(resourceName) { context.resources.getIdentifier(resourceName, "drawable", context.packageName) }
    if (id != 0) {
        Image(painter = painterResource(id), contentDescription = null, modifier = modifier)
    } else {
        Icon(fallback, contentDescription = null, tint = if (tint == Color.Unspecified) LocalContentColor.current else tint, modifier = modifier)
    }
}

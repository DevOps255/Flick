package com.flick.app.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.flick.app.data.ProfileStore
import com.flick.app.ui.components.Avatar
import com.flick.app.ui.components.FlickButton
import com.flick.app.ui.theme.Accent
import com.flick.app.ui.theme.pressable
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class ProfileViewModel @Inject constructor(private val store: ProfileStore) : ViewModel() {
    val profile = store.profile

    fun setName(name: String) {
        viewModelScope.launch { store.setName(name) }
    }

    fun setPhoto(uri: Uri) {
        viewModelScope.launch { store.setPhoto(uri) }
    }

    fun removePhoto() {
        viewModelScope.launch { store.removePhoto() }
    }
}

/** The user's name and photo. The photo stays on this phone. The name is the one other phones see. */
@Composable
fun ProfileScreen(viewModel: ProfileViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle(initialValue = null)
    // The photo picker of the system needs no permission.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::setPhoto) }

    var name by remember { mutableStateOf("") }
    // The field starts from the saved name once it is read, and is not overwritten while the user types.
    LaunchedEffect(profile?.name) { if (name.isEmpty()) name = profile?.name.orEmpty() }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Box(Modifier.pressable({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })) {
            Avatar(profile?.name ?: "You", size = 112.dp, photoPath = profile?.photoPath)
            Box(
                Modifier.align(Alignment.BottomEnd).size(36.dp).background(Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.PhotoCamera, contentDescription = "Change photo", tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
        if (profile?.photoPath != null) TextButton(onClick = viewModel::removePhoto) { Text("Remove photo") }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(24) },
            label = { Text("Your name") },
            singleLine = true,
            supportingText = { Text("Other phones show this name. A new name reaches them after you restart Flick.") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        FlickButton("Save name", Icons.Filled.Check, { viewModel.setName(name) }, Accent, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Text(profile?.flickId.orEmpty(), style = MaterialTheme.typography.labelMedium)
    }
}

package com.flick.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flick.app.data.ProfileStore
import com.flick.app.ui.components.Avatar
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(store: ProfileStore) : ViewModel() {
    val profile = store.profile
}

@Composable
fun ProfileScreen(viewModel: ProfileViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle(initialValue = null)
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        Avatar(profile?.name ?: "You", size = 96.dp)
        Spacer(Modifier.height(16.dp))
        Text(profile?.name.orEmpty(), style = MaterialTheme.typography.titleLarge)
        Text(profile?.flickId.orEmpty(), style = MaterialTheme.typography.labelMedium)
    }
}

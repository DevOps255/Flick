package com.flick.app.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flick.app.core.Profile
import com.flick.app.data.ProfileStore
import com.flick.app.data.SendSelection
import com.flick.app.data.db.FriendDao
import com.flick.app.data.db.FriendEntity
import com.flick.app.data.db.TransferDao
import com.flick.app.data.db.TransferEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val profile: Profile? = null,
    val friends: List<FriendEntity> = emptyList(),
    val activity: List<TransferEntity> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val profileStore: ProfileStore,
    friendDao: FriendDao,
    transferDao: TransferDao,
    private val selection: SendSelection,
) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(
        profileStore.profile,
        friendDao.observeAll(),
        transferDao.recent(RECENT_LIMIT),
    ) { profile, friends, activity -> HomeUiState(profile, friends, activity) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch { profileStore.ensureCreated() }
    }

    fun onFilesPicked(uris: List<Uri>) = selection.select(uris)

    private companion object {
        const val RECENT_LIMIT = 20
    }
}

package com.flick.app.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flick.app.core.Profile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.profileDataStore by preferencesDataStore(name = "profile")

/** Persists the local user's name and Flick ID. */
@Singleton
class ProfileStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val nameKey = stringPreferencesKey("name")
    private val idKey = stringPreferencesKey("flick_id")

    val profile: Flow<Profile> = context.profileDataStore.data.map { prefs ->
        Profile(name = prefs[nameKey] ?: Build.MODEL, flickId = prefs[idKey] ?: "flk#-----")
    }

    /** Generates the Flick ID on first launch. Later calls change nothing. */
    suspend fun ensureCreated() {
        context.profileDataStore.edit { prefs ->
            if (prefs[idKey] == null) prefs[idKey] = "flk#" + Random.nextInt(10_000, 100_000)
        }
    }
}

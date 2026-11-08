package com.flick.app.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this phone works with one other phone or with several.
 *
 * By default only the closest phone counts: files go to it, and files are accepted from it.
 * The user turns on "several phones" with a button, then every ready phone is a recipient
 * when sending, and every phone nearby may send here.
 */
@Singleton
class ShareMode @Inject constructor() {
    private val _multiple = MutableStateFlow(false)
    val multiple: StateFlow<Boolean> = _multiple.asStateFlow()

    fun setMultiple(value: Boolean) {
        _multiple.value = value
    }
}

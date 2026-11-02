package com.flick.app

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.flick.app.ui.Permissions
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.flick_ffi.BleFrameData
import uniffi.flick_ffi.ReceiveObserver
import uniffi.flick_ffi.TransferEvent
import uniffi.flick_ffi.bleFrameDecode
import uniffi.flick_ffi.bleFrameEncode
import uniffi.flick_ffi.generateIdentity
import uniffi.flick_ffi.startNode

/**
 * Starts the real app on an emulator. An emulator has no Bluetooth, so this checks what can be
 * checked without a phone: the app opens and stays open, and the Rust library loads and matches the
 * Kotlin bindings. Both failed on a real phone before this test existed.
 */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*Permissions.all())

    @Test
    fun appOpensAndStaysOpen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Permissions, discovery and the node all start in these seconds.
            Thread.sleep(4_000)
            assertNotEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test
    fun rustLibraryLoadsAndMatchesTheKotlinBindings() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val identity = generateIdentity()
        assertTrue(identity.contains("BEGIN CERTIFICATE"))

        val node = startNode(
            identity,
            "Test phone",
            File(context.cacheDir, "smoke-inbox").absolutePath,
            object : ReceiveObserver {
                override fun onEvent(event: TransferEvent) = Unit
            },
        )
        assertTrue(node.port().toInt() > 0)

        // A frame goes through Rust and comes back unchanged.
        val announcement = node.announcement(ByteArray(4), ByteArray(4))
        val decoded = bleFrameDecode(bleFrameEncode(BleFrameData(announcement, false, 0u)))
        assertNotNull(decoded)
        assertEquals(announcement.port, decoded!!.announcement.port)
        node.close()
    }
}

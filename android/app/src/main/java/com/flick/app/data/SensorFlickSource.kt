package com.flick.app.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import uniffi.flick_ffi.FlickDetector
import uniffi.flick_ffi.FlickEvent

/**
 * Turns the accelerometer into a stream of Flicks.
 *
 * The detection itself lives in the Rust crate `flick-gesture`, so Android and
 * desktop share one implementation. The sensor is registered only while the
 * flow is collected.
 */
@Singleton
class SensorFlickSource @Inject constructor(@ApplicationContext private val context: Context) {

    fun flicks(): Flow<FlickEvent> = callbackFlow {
        val manager = context.getSystemService(SensorManager::class.java)
        val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || accelerometer == null) {
            close()
            return@callbackFlow
        }
        val detector = FlickDetector()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val tMs = (event.timestamp / NANOS_PER_MS).toULong()
                detector.push(tMs, event.values[0], event.values[1], event.values[2])?.let { trySend(it) }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        manager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        awaitClose {
            manager.unregisterListener(listener)
            detector.close()
        }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}

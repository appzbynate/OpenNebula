package org.opennebula.air.render

import org.opennebula.air.imu.Orientation
import java.util.concurrent.atomic.AtomicReference

/**
 * Latest raw fusion + display quat for motion CSV / logcat diagnosis.
 * Written on the GL thread; read from the IMU thread (volatile snapshot).
 */
object DisplayPoseBridge {
    data class Snapshot(
        val raw: Orientation = Orientation.IDENTITY,
        val display: Orientation = Orientation.IDENTITY,
        val holding: Boolean = false,
        val lagDeg: Float = 0f,
        val speedHatDps: Float = 0f,
        /** View-space of the default panel center under the *display* view. */
        val objX: Float = 0f,
        val objY: Float = 0f,
        val objZ: Float = 0f,
        /** Same point under the *raw* view — difference shows display lag on-screen. */
        val rawObjX: Float = 0f,
        val rawObjY: Float = 0f,
        val rawObjZ: Float = 0f,
        val frameNs: Long = 0L,
    )

    private val snap = AtomicReference(Snapshot())

    fun publish(s: Snapshot) {
        snap.set(s)
    }

    fun get(): Snapshot = snap.get()
}

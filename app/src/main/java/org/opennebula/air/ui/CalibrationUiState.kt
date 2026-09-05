package org.opennebula.air.ui

/**
 * Guided still → Anchor calibration shown on the phone.
 * Progress is quiet gyro samples / warmup (stalls if you move).
 */
enum class CalibrationPhase {
    Hidden,
    HoldStill,
    Anchoring,
    Done,
    Failed,
}

data class CalibrationUiState(
    val phase: CalibrationPhase = CalibrationPhase.Hidden,
    /** 0..1 quiet bias progress. */
    val progress: Float = 0f,
    val rateEmaDps: Float = 0f,
    val message: String = "",
)

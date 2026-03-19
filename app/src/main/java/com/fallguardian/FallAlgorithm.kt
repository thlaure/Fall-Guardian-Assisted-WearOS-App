package com.fallguardian

import kotlin.math.sqrt

/**
 * PSP-optimized fall detection algorithm.
 *
 * Three phases:
 *   Phase 1 (Free-fall): ||accel|| < freeFallThresholdG for >= freeFallMinMs
 *   Phase 2 (Impact):    ||accel|| > impactThresholdG
 *   Phase 3 (Tilt):      angle from upright > tiltThresholdDeg (via gravity vector)
 *
 * Trigger: (Phase1 AND Phase2) OR (Phase2 AND Phase3)
 *
 * No immobility requirement — fires immediately after the last qualifying phase.
 */
class FallAlgorithm(
    var freeFallThresholdG: Float = 0.5f,
    var impactThresholdG: Float = 2.5f,
    var tiltThresholdDeg: Float = 45f,
    var freeFallMinMs: Long = 80L
) {
    // State
    private var freeFallStartMs: Long = 0L
    private var freeFallActive = false
    private var impactDetected = false
    private var impactTimeMs: Long = 0L

    // Gravity low-pass filter (for tilt calculation)
    private val gravity = FloatArray(3) { 0f }
    private val alpha = 0.8f // low-pass filter coefficient

    /** Reset all state (call after a fall fires or on service restart). */
    fun reset() {
        freeFallActive = false
        freeFallStartMs = 0L
        impactDetected = false
        impactTimeMs = 0L
        gravity.fill(0f)
    }

    /**
     * Process one sensor sample.
     * @param ax/ay/az  raw accelerometer values in m/s² (device frame)
     * @param nowMs     current time in milliseconds
     * @return          true if a fall was just detected
     */
    fun processSample(ax: Float, ay: Float, az: Float, nowMs: Long): Boolean {
        // Gravity isolation via low-pass filter
        gravity[0] = alpha * gravity[0] + (1 - alpha) * ax
        gravity[1] = alpha * gravity[1] + (1 - alpha) * ay
        gravity[2] = alpha * gravity[2] + (1 - alpha) * az

        val normG = norm(ax, ay, az) / 9.81f // convert to g-units

        // --- Phase 1: Free-fall detection ---
        if (normG < freeFallThresholdG) {
            if (!freeFallActive) {
                freeFallActive = true
                freeFallStartMs = nowMs
            }
        } else {
            freeFallActive = false
        }
        val freeFallQualified = freeFallActive && (nowMs - freeFallStartMs >= freeFallMinMs)

        // --- Phase 2: Impact detection ---
        if (normG > impactThresholdG) {
            impactDetected = true
            impactTimeMs = nowMs
        }
        // Impact window: keep it alive for 2 seconds after detection
        val impactActive = impactDetected && (nowMs - impactTimeMs < 2000L)

        // --- Phase 3: Tilt detection ---
        val tiltDeg = tiltAngleDeg()
        val tiltActive = tiltDeg > tiltThresholdDeg

        // --- Trigger rule ---
        val fallDetected = (freeFallQualified && impactActive) || (impactActive && tiltActive)

        return fallDetected
    }

    /** Angle in degrees between gravity vector and vertical (upright = 0°). */
    private fun tiltAngleDeg(): Float {
        val gNorm = norm(gravity[0], gravity[1], gravity[2])
        if (gNorm < 0.01f) return 0f
        // Dot product with world-up vector (0, 0, 1) normalised
        val cosAngle = gravity[2] / gNorm
        val clampedCos = cosAngle.coerceIn(-1f, 1f)
        return Math.toDegrees(Math.acos(clampedCos.toDouble())).toFloat()
    }

    private fun norm(x: Float, y: Float, z: Float) =
        sqrt((x * x + y * y + z * z).toDouble()).toFloat()
}

package com.robotics.camera

import org.json.JSONObject

/**
 * Encapsulates the camera parameters for deterministic manual capture or comparative auto-mode testing.
 *
 * @property focusDistance Lens focus distance in diopters (1 / distance_in_meters).
 *                         0.0f corresponds to infinity focus; 10.0f corresponds to 10cm.
 *                         Used when [autoMode] is false.
 * @property iso Sensor sensitivity (ISO rating). Common values: 50, 100, 200, 400, 800.
 *               Used when [autoMode] is false.
 * @property exposureTimeNs Exposure duration in nanoseconds (e.g., 10_000_000L = 10ms).
 *                          Used when [autoMode] is false.
 * @property autoMode When true (default), enables standard Android Camera2 3A routines (AF, AE, AWB)
 *                    and ISP post-processing to emulate the default Android camera behavior.
 *                    When false, enforces strict manual hardware determinism.
 * @property oisEnabled When true, requests Optical Image Stabilization (LENS_OPTICAL_STABILIZATION_MODE_ON).
 * @property eisEnabled When true, requests Electronic Image Stabilization (CONTROL_VIDEO_STABILIZATION_MODE_ON).
 */
data class CameraSettings(
    val focusDistance: Float = 0.0f,
    val iso: Int = 100,
    val exposureTimeNs: Long = 10_000_000L,
    val autoMode: Boolean = true,
    val oisEnabled: Boolean = true,
    val eisEnabled: Boolean = true
) {
    /**
     * Serializes the camera settings into a JSONObject for HTTP responses.
     *
     * @return JSONObject representing the current camera configuration.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("control_mode", if (autoMode) "auto" else "manual")
            put("auto_mode", autoMode)
            put("focus_distance", focusDistance.toDouble())
            put("iso", iso)
            put("exposure_time_ns", exposureTimeNs)
            put("exposure_time_ms", exposureTimeNs / 1_000_000.0)
            put("ois_enabled", oisEnabled)
            put("eis_enabled", eisEnabled)
        }
    }

    companion object {
        /**
         * Parses a JSON payload from POST /config and merges it with the current settings.
         *
         * When switching control_mode to "auto" (or auto_mode to true) without explicitly
         * specifying ois_enabled or eis_enabled, both stabilization modes default to true
         * to match standard Android camera behavior. When switching back to "manual", they
         * default to false unless explicitly overridden in the JSON payload.
         *
         * @param json Incoming JSONObject from the HTTP request body.
         * @param current The currently active [CameraSettings] used for unspecified fields.
         * @return A new [CameraSettings] instance prior to hardware capability clamping.
         * @throws org.json.JSONException If a provided field cannot be coerced to its expected type.
         */
        fun fromJson(json: JSONObject, current: CameraSettings): CameraSettings {
            val modeExplicitlyProvided =
                json.has("control_mode") || json.has("mode") || json.has("auto_mode")

            val resolvedAutoMode = when {
                json.has("control_mode") ->
                    json.getString("control_mode").equals("auto", ignoreCase = true)
                json.has("mode") ->
                    json.getString("mode").equals("auto", ignoreCase = true)
                json.has("auto_mode") ->
                    json.getBoolean("auto_mode")
                else -> current.autoMode
            }

            val focus = if (json.has("focus_distance")) {
                json.getDouble("focus_distance").toFloat()
            } else {
                current.focusDistance
            }

            val iso = if (json.has("iso")) {
                json.getInt("iso")
            } else {
                current.iso
            }

            val expNs = if (json.has("exposure_time_ns")) {
                json.getLong("exposure_time_ns")
            } else if (json.has("exposure_time")) {
                val raw = json.getDouble("exposure_time")
                if (raw < 1000.0) {
                    (raw * 1_000_000).toLong()
                } else {
                    raw.toLong()
                }
            } else {
                current.exposureTimeNs
            }

            val ois = when {
                json.has("ois_enabled") -> json.getBoolean("ois_enabled")
                modeExplicitlyProvided -> resolvedAutoMode
                else -> current.oisEnabled
            }

            val eis = when {
                json.has("eis_enabled") -> json.getBoolean("eis_enabled")
                modeExplicitlyProvided -> resolvedAutoMode
                else -> current.eisEnabled
            }

            return CameraSettings(
                focusDistance = focus,
                iso = iso,
                exposureTimeNs = expNs,
                autoMode = resolvedAutoMode,
                oisEnabled = ois,
                eisEnabled = eis
            )
        }
    }
}

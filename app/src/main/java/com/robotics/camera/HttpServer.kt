package com.robotics.camera

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.HashMap

/**
 * Embedded HTTP Server running on port 8080 using NanoHTTPD.
 * Serves deterministic visual servoing endpoints:
 * - GET  /capture : Triggers and returns a raw JPEG frame.
 * - POST /config  : Dynamically configures focus, ISO, and exposure time.
 * - GET  /status  : Returns current camera telemetry and capture statistics.
 */
class HttpServer(
    port: Int = 8080,
    private val cameraController: CameraController,
    private val onCaptureServedListener: ((Long) -> Unit)? = null
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "HttpServer"
        private const val MIME_JPEG = "image/jpeg"
        private const val MIME_JSON = "application/json"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "Incoming HTTP request: $method $uri from ${session.remoteIpAddress}")

        return try {
            when {
                // 1. FRAME CAPTURE ENDPOINT
                method == Method.GET && uri == "/capture" -> handleCaptureRequest(session)

                // 2. DYNAMIC CONFIGURATION ENDPOINT
                method == Method.POST && uri == "/config" -> handleConfigRequest(session)

                // 3. STATUS / TELEMETRY ENDPOINT
                method == Method.GET && (uri == "/status" || uri == "/") -> handleStatusRequest()

                // 4. INVALID ENDPOINTS
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_JSON,
                    JSONObject().put("error", "Endpoint not found: $uri").toString()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request: ${e.message}", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_JSON,
                JSONObject().apply {
                    put("error", "Internal server error")
                    put("details", e.message ?: "Unknown error")
                }.toString()
            )
        }
    }

    /**
     * Handles GET /capture:
     * Blocks until the Camera2 pipeline produces a manual frame and hardware metadata.
     * Injects all 22 hardware metadata parameters into the 'X-Camera-Metadata' header.
     * Optionally returns a JSON bundle with Base64 image if ?format=json is passed.
     */
    private fun handleCaptureRequest(session: IHTTPSession): Response {
        return try {
            val capturedFrame = cameraController.captureStillFrame(timeoutMs = 4000)
            val jpegBytes = capturedFrame.jpegBytes
            val metadataJson = capturedFrame.metadata

            // Optional: return full JSON bundle with Base64 image when ?format=json is requested
            val formatParam = session.parameters["format"]?.firstOrNull()
            if (formatParam.equals("json", ignoreCase = true)) {
                val base64Image = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)
                val bundleJson = JSONObject().apply {
                    put("status", "success")
                    put("metadata", metadataJson)
                    put("image_base64", base64Image)
                }
                val jsonResponse = newFixedLengthResponse(
                    Response.Status.OK,
                    MIME_JSON,
                    bundleJson.toString()
                )
                jsonResponse.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                onCaptureServedListener?.invoke(cameraController.totalCapturesServed.get())
                return jsonResponse
            }

            // Default: Raw JPEG stream with complete metadata in X-Camera-Metadata header
            val response = newFixedLengthResponse(
                Response.Status.OK,
                MIME_JPEG,
                ByteArrayInputStream(jpegBytes),
                jpegBytes.size.toLong()
            )

            // Disable all HTTP caching for strict visual servoing freshness
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            response.addHeader("Pragma", "no-cache")
            response.addHeader("Expires", "0")

            // 1. INJECT COMPLETE HARDWARE METADATA JSON IN HEADER
            response.addHeader("X-Camera-Metadata", metadataJson.toString())

            // 2. Individual convenience headers for backward compatibility
            val expNs = metadataJson.optLong("sensor_exposure_time", cameraController.currentSettings.exposureTimeNs)
            response.addHeader("X-Camera-Focus-Distance", metadataJson.optDouble("lens_focus_distance").toString())
            response.addHeader("X-Camera-ISO", metadataJson.optInt("sensor_sensitivity").toString())
            response.addHeader("X-Camera-Exposure-Time-Ns", expNs.toString())
            response.addHeader("X-Camera-Exposure-Time-Ms", (expNs / 1_000_000.0).toString())
            response.addHeader("X-Camera-Sensor-Timestamp", metadataJson.optLong("sensor_timestamp").toString())

            onCaptureServedListener?.invoke(cameraController.totalCapturesServed.get())
            response

        } catch (te: java.util.concurrent.TimeoutException) {
            Log.e(TAG, "Capture timed out", te)
            newFixedLengthResponse(
                Response.Status.REQUEST_TIMEOUT,
                MIME_JSON,
                JSONObject().put("error", "Timeout waiting for camera frame").toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture frame", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_JSON,
                JSONObject().put("error", e.message ?: "Capture failed").toString()
            )
        }
    }

    /**
     * Handles POST /config:
     * Parses JSON body containing {"focus_distance": Float, "iso": Int, "exposure_time": Long}
     * and applies updates to the live CameraCaptureSession.
     */
    private fun handleConfigRequest(session: IHTTPSession): Response {
        val bodyContent = extractRequestBody(session)

        if (bodyContent.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_JSON,
                JSONObject().put("error", "Empty or missing request body").toString()
            )
        }

        return try {
            val json = JSONObject(bodyContent)
            val updated = CameraSettings.fromJson(json, cameraController.currentSettings)
            val applied = cameraController.updateManualSettings(updated)

            val responseJson = JSONObject().apply {
                put("status", "success")
                put("message", "Camera parameters updated")
                put("applied_settings", applied.toJson())
            }

            newFixedLengthResponse(Response.Status.OK, MIME_JSON, responseJson.toString())

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse or apply config", e)
            newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_JSON,
                JSONObject().apply {
                    put("error", "Invalid JSON payload")
                    put("details", e.message)
                }.toString()
            )
        }
    }

    /**
     * Handles GET /status:
     * Returns JSON metadata about current sensor settings and system statistics.
     */
    private fun handleStatusRequest(): Response {
        val statusJson = JSONObject().apply {
            put("status", "online")
            put("device_model", android.os.Build.MODEL)
            put("android_version", android.os.Build.VERSION.RELEASE)
            put("captures_served", cameraController.totalCapturesServed.get())
            put("current_settings", cameraController.currentSettings.toJson())
        }

        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, statusJson.toString())
    }

    /**
     * Extracts request body from NanoHTTPD session across standard Content-Types.
     */
    private fun extractRequestBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
            val postData = files["postData"]
            if (!postData.isNullOrBlank()) {
                return postData
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseBody failed, reading direct stream", e)
        }

        // Fallback to direct stream reading
        return try {
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength > 0) {
                val buffer = ByteArray(contentLength)
                var bytesRead = 0
                while (bytesRead < contentLength) {
                    val read = session.inputStream.read(buffer, bytesRead, contentLength - bytesRead)
                    if (read == -1) break
                    bytesRead += read
                }
                String(buffer, 0, bytesRead, Charsets.UTF_8)
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}

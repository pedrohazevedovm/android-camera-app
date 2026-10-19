package com.robotics.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.PowerManager
import android.view.TextureView
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * MainActivity serves as the headless host for the camera pipeline and HTTP server.
 *
 * It maintains the TextureView surface to drive the Camera2 HAL, handles runtime permissions,
 * holds a WakeLock and FLAG_KEEP_SCREEN_ON to prevent device sleep on the robotic platform,
 * and displays IP address and telemetry.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var tvServerStatus: TextView
    private lateinit var tvIpAddress: TextView
    private lateinit var tvCameraInfo: TextView
    private lateinit var tvCaptureCount: TextView

    private lateinit var cameraController: CameraController
    private var httpServer: HttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            initializeCameraAndServer()
        } else {
            Toast.makeText(this, R.string.permission_rationale, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Prevent the device from sleeping while mounted on the robot arm
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        acquireWakeLock()

        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvCameraInfo = findViewById(R.id.tvCameraInfo)
        tvCaptureCount = findViewById(R.id.tvCaptureCount)

        cameraController = CameraController(this) { settings ->
            runOnUiThread {
                updateCameraSettingsDisplay(settings)
            }
        }

        checkAndRequestPermissions()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CamApp::HeadlessRoboticsWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // 12 hours timeout guard
        }
    }

    private fun checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            initializeCameraAndServer()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun initializeCameraAndServer() {
        // Display IP Address
        val ip = NetworkUtils.getLocalIpAddress()
        tvIpAddress.text = "http://$ip:8080"

        // Initialize HTTP Server on port 8080
        startHttpServer()

        // Bind TextureView lifecycle to open Camera2
        if (textureView.isAvailable) {
            openCamera(textureView.surfaceTexture!!, textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openCamera(surface, width, height)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    // Handled internally by CameraController
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    cameraController.closeCamera()
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    // Frame rendered to TextureView
                }
            }
        }
    }

    private fun openCamera(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        val w = if (width > 0) width else 1920
        val h = if (height > 0) height else 1080
        cameraController.openCamera(surfaceTexture, w, h)
    }

    private fun startHttpServer() {
        try {
            if (httpServer == null) {
                httpServer = HttpServer(8080, cameraController) { totalCaptures ->
                    runOnUiThread {
                        tvCaptureCount.text = "Captures Served: $totalCaptures"
                    }
                }
                httpServer?.start()
                tvServerStatus.text = "SERVER ONLINE (PORT 8080)"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            tvServerStatus.text = "SERVER ERROR: ${e.message}"
        }
    }

    private fun updateCameraSettingsDisplay(settings: CameraSettings) {
        val modeLabel = if (settings.autoMode) "AUTO (3A)" else "MANUAL"
        val oisLabel = if (settings.oisEnabled) "ON" else "OFF"
        val eisLabel = if (settings.eisEnabled) "ON" else "OFF"
        if (settings.autoMode) {
            tvCameraInfo.text = "Mode: $modeLabel | OIS: $oisLabel | EIS: $eisLabel"
        } else {
            val expMs = String.format("%.2f", settings.exposureTimeNs / 1_000_000.0)
            val focusD = String.format("%.2f", settings.focusDistance)
            tvCameraInfo.text = "Mode: $modeLabel | ISO: ${settings.iso} | Exp: ${expMs} ms | Focus: ${focusD} D | OIS: $oisLabel | EIS: $eisLabel"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraController.closeCamera()

        httpServer?.stop()
        httpServer = null

        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }
}

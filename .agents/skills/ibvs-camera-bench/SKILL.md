---
name: ibvs-camera-bench
description: >-
  Builds, deploys, tunnels over ADB USB, and validates the headless Android IP
  camera endpoints (/capture, /config, /status) and Camera2 hardware metadata
  for Image-Based Visual Servoing (IBVS) and camera stabilization analysis.
---

# IBVS Camera Benchmark & Verification Skill

Use this skill when compiling, deploying, configuring, or verifying the `Cam-App` Android vision sensor and its HTTP metadata contract.

## 1. Build and Deploy Workflow

1. Compile the debug APK from the repository root:
   ```powershell
   ./gradlew assembleDebug
   ```
2. Verify that the target Android device is connected via ADB:
   ```powershell
   adb devices
   ```
3. Install the APK and launch `MainActivity`:
   ```powershell
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.robotics.camera/.MainActivity
   ```

## 2. Deterministic Low-Latency USB Tunneling

When the Android device is mounted on the robotic arm via USB, prefer ADB port forwarding over Wi-Fi to eliminate wireless jitter during IBVS loops and stabilization benchmarks:

```powershell
adb forward tcp:8080 tcp:8080
```

Once forwarded, all endpoints are accessible locally at `http://127.0.0.1:8080`.

## 3. Endpoint & Stabilization Metadata Verification

Run the verification script [verify_capture_metadata.py](./scripts/verify_capture_metadata.py) to validate the HTTP contract, inspect the `X-Camera-Metadata` header, and measure frame capture latency:

```powershell
python .agents/skills/ibvs-camera-bench/scripts/verify_capture_metadata.py --url http://127.0.0.1:8080 --samples 5
```

## 4. Diagnostic Logcat Inspection

If a capture times out (`HTTP 408`) or a hardware parameter is clamped/ignored by the OEM HAL, inspect the Camera2 and HTTP logs:

```powershell
adb logcat -d -s CameraController:V HttpServer:V
```

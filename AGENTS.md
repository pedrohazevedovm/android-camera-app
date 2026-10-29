# AGENTS.md — Headless Android IP Camera for Robotic Visual Servoing (`Cam-App`)

## 1. Project Overview
This repository contains an embedded, headless Android application written in Kotlin (`minSdk = 24`, `targetSdk = 34`) designed to operate as a deterministic vision sensor mounted on a robotic arm. Its two core objectives are:
1. **Dataset Collection and Control for IBVS (Image-Based Visual Servoing)**: Deliver low-latency 1080p JPEG frames over HTTP without non-deterministic interference from automated 3A algorithms (AF, AE, AWB) or ISP post-processing filters.
2. **Camera Stabilization Analysis**: Extract frame-synchronized hardware metadata (`TotalCaptureResult`) and enable controlled manipulation of optical and electronic parameters (including dynamic toggling of OIS and EIS for comparative stabilization experiments).

---

## 2. Architectural Map

| File | Responsibility |
| :--- | :--- |
| [`MainActivity.kt`](app/src/main/java/com/robotics/camera/MainActivity.kt) | Hosts the `TextureView` (required to keep the Camera2 pipeline active), manages `PARTIAL_WAKE_LOCK` + `FLAG_KEEP_SCREEN_ON`, and updates the telemetry HUD in [`activity_main.xml`](app/src/main/res/layout/activity_main.xml). |
| [`CameraController.kt`](app/src/main/java/com/robotics/camera/CameraController.kt) | Low-level Camera2 API controller (`CameraDevice`, `CameraCaptureSession`, `ImageReader`), strict manual parameter overrides, blocking frame capture (`CountDownLatch(2)`), and metadata extraction from `TotalCaptureResult`. |
| [`HttpServer.kt`](app/src/main/java/com/robotics/camera/HttpServer.kt) | Embedded HTTP server (NanoHTTPD on port `8080`) exposing `GET /capture`, `POST /config`, and `GET /status`. |
| [`CameraConfig.kt`](app/src/main/java/com/robotics/camera/CameraConfig.kt) | `CameraSettings` data model and JSON serialization/deserialization for runtime-configurable camera parameters. |
| [`NetworkUtils.kt`](app/src/main/java/com/robotics/camera/NetworkUtils.kt) | Resolves the primary local IPv4 address, prioritizing `wlan` (Wi-Fi) and `eth`/`rndis` (Ethernet/USB Tethering) interfaces. |

---

## 3. Global Rules and Project Invariants

In addition to the specific rules in `.agents/rules/`, every agent operating in this repository **MUST** adhere to the following invariants:

1. **Documentation as Code Compliance (LAW 1)**:
   * **Descriptive Names**: Avoid ambiguous abbreviations (`exposureTimeNs` instead of `exp`).
   * **Small, Single-Purpose Functions**: Each function must do one thing well.
   * **Mandatory English Docstrings/KDocs**: Every public function or class must include English documentation detailing its description, parameters (`@param` / `Args:`), return value (`@return` / `Returns:`), and exceptions (`@throws` / `Raises:`).
   * **Strict Prohibitions**: Never leave commented-out code, redundant comments that merely restate the code, or `TODO`s without an associated issue/ticket reference.
   * **Living README**: Any modification to HTTP endpoints, ports, configuration parameters, or metadata keys requires an immediate update to [`README.md`](README.md).
2. **Zero Disk I/O on the Capture Path**:
   * The `ImageReader` $\rightarrow$ `ByteArray` $\rightarrow$ `NanoHTTPD` pipeline must execute entirely in RAM to minimize latency and jitter in the robotic visual servoing loop.
3. **Pure Camera2 API**:
   * Maintain direct usage of `android.hardware.camera2.*`. Do not replace the core pipeline with high-level abstractions (such as CameraX without explicit interop) that obscure `TotalCaptureResult` or manual HAL control.

---

## 4. Skill Usage Guide for This Project

When executing tasks in this repository, prioritize loading the following skills on demand:

* **Project-Local Skill**:
  * **`ibvs-camera-bench`** (`.agents/skills/ibvs-camera-bench/SKILL.md`): Use whenever building, installing via ADB, configuring USB port forwarding (`adb forward tcp:8080 tcp:8080`), or validating frame capture and stabilization metadata.
* **Recommended Environment Skills**:
  * **`android-cli`**: Use to manage connected devices/emulators via CLI, execute builds/deployments, and look up official Android SDK documentation for `CameraCharacteristics`, `CaptureRequest`, and `CaptureResult` keys.
  * **`android-profiler`**: Use when analyzing IBVS capture latency, investigating GC pauses from JPEG buffer allocations, diagnosing network/thread jitter, or checking thermal throttling during continuous capture runs via Perfetto.
  * **`testing-setup`**: Use when setting up unit tests (JUnit/Robolectric) for `CameraSettings`, `HttpServer`, and metadata validation.
  * **`android-permissions-security`** / **`android-intent-security`**: Use when auditing `AndroidManifest.xml` or if adding `Intent`/Broadcast receivers for ADB control.
  * **`pptx`**: Use if asked to generate slide decks or visual reports of robotic arm camera stabilization experiments.
* **Skills to Avoid in the Core Pipeline**:
  * **`camerax`**: Do not use to replace `CameraController`, as deterministic sensor control and stabilization auditing rely directly on the Camera2 API.

---

## 5. Recommended Subagents (`define_subagent` / `invoke_subagent`)

For complex tasks, delegate work using `invoke_subagent` or define specialized subagents via `define_subagent` using the specifications in `.agents/agents/`:

1. **[`camera2-hal-specialist`](.agents/agents/camera2-hal-specialist.md)** (`enable_write_tools: false`):
   * **When to use**: Before adding new hardware keys (`CaptureRequest.*` or `CaptureResult.*`), such as `LENS_STATE`, `LENS_INTRINSIC_CALIBRATION`, `SENSOR_ROLLING_SHUTTER_SKEW`, or OIS/EIS modes.
   * **Suggested base prompt**: *"Verify the availability, data type, physical units, and hardware level support (`INFO_SUPPORTED_HARDWARE_LEVEL_FULL` vs `LEVEL_3`, API 24+) for the requested Camera2 keys. Identify whether the parameter can return `null` on OEM devices and recommend an appropriate fallback value."*
2. **[`ibvs-api-verifier`](.agents/agents/ibvs-api-verifier.md)** (`enable_write_tools: true`):
   * **When to use**: After modifying `CameraController.kt`, `CameraConfig.kt`, or `HttpServer.kt`.
   * **Suggested base prompt**: *"Run `./gradlew assembleDebug` to validate compilation, verify that `extractMetadata` and `buildFallbackMetadata` contain the exact same JSON keys, confirm that `README.md` reflects all current keys, and if a device is connected via ADB, run `.agents/skills/ibvs-camera-bench/scripts/verify_capture_metadata.py`."*

---

## 6. Quick Development Commands

```powershell
# Build Debug APK
./gradlew assembleDebug

# Install on connected device via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Create deterministic USB tunnel (avoids Wi-Fi latency/jitter on the robotic arm)
adb forward tcp:8080 tcp:8080

# Monitor camera controller and HTTP server logs
adb logcat -s CameraController:V HttpServer:V
```

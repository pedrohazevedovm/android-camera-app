# Camera2 Determinism and Stabilization Analysis Rules

These rules apply to all modifications in the camera pipeline ([`CameraController.kt`](../../app/src/main/java/com/robotics/camera/CameraController.kt) and [`CameraConfig.kt`](../../app/src/main/java/com/robotics/camera/CameraConfig.kt)).

## 1. Strict Manual Control (3A and Post-Processing Bypass)
* The master control mode must remain manual (`CONTROL_MODE = CONTROL_MODE_OFF`), keeping `CONTROL_AF_MODE_OFF`, `CONTROL_AE_MODE_OFF`, and `CONTROL_AWB_MODE_OFF` by default to guarantee photometric and geometric repeatability for IBVS.
* Whenever `CONTROL_AWB_MODE` is set to `OFF`, always maintain `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX` with explicit RGGB gains (`RggbChannelVector`) and a valid `ColorSpaceTransform` to prevent OEM HAL chromatic tint artifacts.
* Post-processing filters (`NOISE_REDUCTION_MODE`, `EDGE_MODE`, `COLOR_CORRECTION_ABERRATION_MODE`, `HOT_PIXEL_MODE`, `SHADING_MODE`) must remain `OFF` by default.

## 2. Dynamic Stabilization Control (OIS and EIS)
* To enable comparative camera stabilization experiments on the robotic arm, both **Optical Image Stabilization (`LENS_OPTICAL_STABILIZATION_MODE`)** and **Electronic/Video Image Stabilization (`CONTROL_VIDEO_STABILIZATION_MODE`)** can be **dynamically enabled or disabled** via [`CameraSettings`](../../app/src/main/java/com/robotics/camera/CameraConfig.kt) (`POST /config`).
* **Default State**: Both must default to `OFF` (`LENS_OPTICAL_STABILIZATION_MODE_OFF` and `CONTROL_VIDEO_STABILIZATION_MODE_OFF`) to preserve hand-eye calibration during standard IBVS runs, and only switch to `ON` when explicitly requested via configuration.
* **Hardware Capability Validation**: Before applying `ON` to OIS or EIS on the `CaptureRequest.Builder`, verify hardware support against `CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION` and `CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES`. If unsupported by the device, safely fall back to `OFF` without throwing an unhandled exception.

## 3. Physical Range Clamping (`clampSettings`)
* No externally supplied parameter (`focusDistance`, `iso`, `exposureTimeNs`, etc.) may be injected into a `CaptureRequest` without first passing through `clampSettings()`, enforcing:
  * `SENSOR_INFO_SENSITIVITY_RANGE` for ISO sensitivity.
  * `SENSOR_INFO_EXPOSURE_TIME_RANGE` for exposure duration in nanoseconds.
  * `[0.0f, LENS_INFO_MINIMUM_FOCUS_DISTANCE]` for focus distance in diopters.

## 4. Synchronization and Metadata Parity (`TotalCaptureResult`)
* Frame capture in `captureStillFrame()` must remain thread-safe under `captureLock` (`ReentrantLock`) and await both the `ImageReader` buffer and the `TotalCaptureResult` callback.
* **Mandatory Key Parity**: Every JSON key added to `extractMetadata(result, request)` **MUST** also be present (with a safe default value) in `buildFallbackMetadata(request)`.
* **Hardware Null-Safety**: Because different OEM HALs may return `null` for specific keys in `TotalCaptureResult`, every `result.get(CaptureResult.*)` call must provide a deterministic fallback value via the Elvis operator (`?:`).

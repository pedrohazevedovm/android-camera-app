# HTTP API Contract, Low Latency, and Documentation Rules

These rules apply to the embedded HTTP server ([`HttpServer.kt`](../../app/src/main/java/com/robotics/camera/HttpServer.kt)), configuration serialization ([`CameraConfig.kt`](../../app/src/main/java/com/robotics/camera/CameraConfig.kt)), and project documentation.

## 1. HTTP Contract Stability (Port 8080)
* Preserve backward compatibility across the three core endpoints consumed by the robotic arm client:
  1. `GET /capture` (returns raw `image/jpeg` bytes with metadata in the `X-Camera-Metadata` header, plus support for `?format=json` returning `image_base64`).
  2. `POST /config` (updates manual parameters in real time on the active `CameraCaptureSession` without closing the camera device).
  3. `GET /status` (returns device telemetry, total captures served, and `current_settings`).
* **JSON Key Stability**: Never rename or remove existing JSON keys in `X-Camera-Metadata`, `POST /config`, or `GET /status` without explicit user approval, as external IBVS and dataset analysis scripts depend on these exact key names.

## 2. Real-Time and Anti-Caching Requirements
* All `GET /capture` responses (both raw JPEG and JSON) must include `Cache-Control: no-cache, no-store, must-revalidate` headers to guarantee the robot controller never processes a stale frame.
* Avoid unnecessary buffer copies or intermediate allocations between `ImageReader` and the `ByteArrayInputStream` passed to `NanoHTTPD`.

## 3. Documentation Synchronization and LAW 1
* Every created or modified public function must include an English KDoc/Docstring detailing its description, parameters, return value, and thrown exceptions.
* Whenever a new key is added to `CameraSettings` (`POST /config`) or to the `extractMetadata` JSON (`X-Camera-Metadata`), update the tables and examples in [`README.md`](../../README.md) in the same step.

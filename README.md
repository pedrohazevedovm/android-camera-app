# Headless Android IP Camera for Robotic Visual Servoing

An embedded, headless Android IP Camera application written in Kotlin designed specifically for robotic visual servoing dataset collection.

The application turns an Android smartphone or single-board computer into a deterministic, zero-latency network vision sensor. It runs an embedded HTTP server on port `8080`, forces the Camera2 HAL into strict manual control (bypassing all automated 3A algorithms and ISP image enhancements), and serves 1080p JPEG frames directly from an `ImageReader` pipeline.

---

## Key Features

- **Strict Determinism**: Disables all OEM 3A algorithms (Auto-Focus, Auto-Exposure, Auto-White Balance) to ensure repeatable illumination and focal planes across datasets.
- **Zero Image Post-Processing**: Disables optical image stabilization (OIS), electronic stabilization (EIS), spatial/temporal noise reduction, edge sharpening, and chromatic aberration correction for unadulterated sensor output.
- **Embedded HTTP Server**: Runs [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) (`2.3.1`) directly on the device on port `8080`.
- **Low-Latency Image Pipeline**: Uses a dedicated `ImageReader` configured for `ImageFormat.JPEG` at 1080p (1920x1080) with blocking thread synchronization.
- **Runtime Reconfiguration**: Dynamically adjust focus distance, ISO sensitivity, and exposure time on the live capture session via `POST /config`.
- **Robot Arm Mounting & Headless Operation**: Enforces `FLAG_KEEP_SCREEN_ON` and holds a `PowerManager.PARTIAL_WAKE_LOCK` to prevent device sleep during long data collection runs.
- **On-Screen Telemetry HUD**: Displays local IP address, port, live sensor parameters (ISO, exposure ms, focus diopters), and total captures served.

---

## Camera2 Manual Hardware Configuration

The camera pipeline is configured in [`CameraController.kt`](app/src/main/java/com/robotics/camera/CameraController.kt) with absolute manual overrides:

| Parameter | Configuration | Purpose |
| :--- | :--- | :--- |
| `CONTROL_MODE` | `CONTROL_MODE_OFF` | Disables automated scene modes and overrides OEM HAL algorithms. |
| `CONTROL_AF_MODE` | `CONTROL_AF_MODE_OFF` | Locks the physical lens actuator at a fixed focus distance (`LENS_FOCUS_DISTANCE`). |
| `CONTROL_AE_MODE` | `CONTROL_AE_MODE_OFF` | Disables auto-exposure; locks shutter time (`SENSOR_EXPOSURE_TIME`) and gain (`SENSOR_SENSITIVITY`). |
| `CONTROL_AWB_MODE` | `CONTROL_AWB_MODE_OFF` | Disables dynamic white balance calculations to prevent chromatic shifts. |
| `COLOR_CORRECTION_MODE` | `TRANSFORM_MATRIX` | Sets neutral daylight gains (`COLOR_CORRECTION_GAINS = 1.8, 1.0, 1.0, 1.6`) to prevent OEM HAL tint bugs when AWB is off. |
| `LENS_OPTICAL_STABILIZATION_MODE` | `OFF` | Prevents voice-coil physical motion caused by robot arm vibrations. |
| `CONTROL_VIDEO_STABILIZATION_MODE` | `OFF` | Prevents electronic image warping and dynamic frame cropping. |
| `NOISE_REDUCTION_MODE` | `OFF` | Preserves raw spatial noise patterns; eliminates frame-to-frame temporal smearing. |
| `EDGE_MODE` | `OFF` | Disables ISP edge sharpening filters for raw pixel fidelity. |
| `COLOR_CORRECTION_ABERRATION_MODE`| `OFF` | Disables computational chromatic aberration correction. |

---

## HTTP Endpoints

The server listens on **port 8080**. The device IP is displayed on screen when the app opens.

### 1. `GET /capture`
Triggers an immediate frame capture from the active sensor stream and returns the raw JPEG byte stream.

- **Method**: `GET`
- **Path**: `/capture`
- **Response**: `200 OK`, `Content-Type: image/jpeg`
- **Response Headers**:
  - `Cache-Control: no-cache, no-store, must-revalidate`
  - `X-Camera-Metadata`: Compact JSON string containing the frame-synchronized hardware metadata keys from `TotalCaptureResult`:
    - `sensor_exposure_time` (ns)
    - `sensor_sensitivity` (ISO)
    - `sensor_timestamp` (hardware monotonic ns)
    - `lens_focus_distance` (diopters)
    - `lens_focal_length` (mm)
    - `lens_intrinsic_calibration` (`[fx, fy, cx, cy, skew]`, when provided by the HAL)
    - `camera_matrix_k` (9 values in ROS row-major order, scaled to the JPEG output)
    - `camera_matrix_width`, `camera_matrix_height`
    - `lens_aperture` (f-number)
    - `control_ae_mode`, `control_af_mode`, `control_awb_mode`
    - `color_correction_mode`
    - `color_correction_gains` (`{"red", "green_even", "green_odd", "blue"}`)
    - `color_correction_transform` (3x3 matrix)
    - `lens_optical_stabilization_mode`, `control_video_stabilization_mode`
    - `noise_reduction_mode`, `edge_mode`, `color_correction_aberration_mode`
    - `scaler_crop_region` (`{"left", "top", "right", "bottom"}`)
    - `jpeg_quality`
  - Individual convenience headers: `X-Camera-Focus-Distance`, `X-Camera-ISO`, `X-Camera-Exposure-Time-Ns`, `X-Camera-Exposure-Time-Ms`, `X-Camera-Sensor-Timestamp`.

#### Optional Query Parameter:
- `GET /capture?format=json`: Returns an `application/json` payload containing the metadata object and the JPEG image encoded in Base64 (`image_base64`).

#### cURL:
```bash
# Capture raw JPEG (metadata in HTTP headers)
curl -v http://<DEVICE_IP>:8080/capture -o frame.jpg

# Inspect the metadata header
curl -s -I http://<DEVICE_IP>:8080/capture | grep -i x-camera-metadata

# Capture JSON bundle with Base64 image
curl -s http://<DEVICE_IP>:8080/capture?format=json -o frame_bundle.json
```

### ROS 2 CameraInfo bridge

The app exposes `camera_matrix_k` in the capture metadata. To publish it as
`sensor_msgs/msg/CameraInfo`, build the `ros2_camera_info_bridge` package in a
ROS 2 workspace:

```bash
cd ~/ros2_ws/src
cp -r /path/to/android-camera-app/ros2_camera_info_bridge .
cd ~/ros2_ws
rosdep install --from-paths src --ignore-src -r -y
colcon build --packages-select ros2_camera_info_bridge
source install/setup.bash
ros2 run ros2_camera_info_bridge android_camera_info \
  --ros-args -p android_url:=http://<DEVICE_IP>:8080
```

The node publishes `/camera/camera_info` with `CameraInfo.k` containing:

```text
[fx, 0, cx, 0, fy, cy, 0, 0, 1]
```

ROS `CameraInfo.K` does not represent skew. Any Android-reported skew remains
available only in `lens_intrinsic_calibration` and is not copied into `K`.

---

### 2. `POST /config`
Dynamically updates the manual camera parameters on the fly without closing the capture session.

- **Method**: `POST`
- **Path**: `/config`
- **Content-Type**: `application/json`

#### Request Body:
```json
{
  "focus_distance": 2.5,
  "iso": 200,
  "exposure_time_ns": 10000000
}
```
*Note: `focus_distance` is in diopters ($0.0$ = infinity, $10.0$ = 10cm). `exposure_time_ns` is in nanoseconds ($10{,}000{,}000 = 10\text{ms} = 1/100\text{s}$). If passing milliseconds, use `"exposure_time": 10.0`.*

#### cURL:
```bash
curl -X POST http://<DEVICE_IP>:8080/config \
     -H "Content-Type: application/json" \
     -d '{"focus_distance": 2.5, "iso": 200, "exposure_time_ns": 10000000}'
```

#### Response:
```json
{
  "status": "success",
  "message": "Camera parameters updated",
  "applied_settings": {
    "focus_distance": 2.5,
    "iso": 200,
    "exposure_time_ns": 10000000,
    "exposure_time_ms": 10.0
  }
}
```

---

### 3. `GET /status`
Returns live telemetry, hardware information, and session capture statistics.

- **Method**: `GET`
- **Path**: `/status`
- **Response**: `200 OK`, `Content-Type: application/json`

#### Response:
```json
{
  "status": "online",
  "device_model": "Pixel 7 Pro",
  "android_version": "14",
  "captures_served": 184,
  "current_settings": {
    "focus_distance": 2.5,
    "iso": 200,
    "exposure_time_ns": 10000000,
    "exposure_time_ms": 10.0
  }
}
```

---

## Python Robotic Servoing Client Example

Here is a minimal Python example demonstrating how to stream frames directly into OpenCV matrices without saving to disk:

```python
import cv2
import json
import numpy as np
import requests

CAMERA_URL = "http://192.168.1.150:8080/capture"
CONFIG_URL = "http://192.168.1.150:8080/config"

# Optional: Set manual exposure & focus before starting
requests.post(CONFIG_URL, json={
    "focus_distance": 2.0,       # 0.5 meters
    "iso": 100,                  # Clean low-noise baseline
    "exposure_time_ns": 10000000 # 10 ms (1/100 sec)
})

# Visual Servoing Capture Loop
while True:
    try:
        response = requests.get(CAMERA_URL, timeout=1.0)
        if response.status_code == 200:
            # 1. Decode JPEG buffer directly to OpenCV BGR array (zero disk I/O)
            img_array = np.frombuffer(response.content, dtype=np.uint8)
            frame = cv2.imdecode(img_array, cv2.IMREAD_COLOR)

            # 2. Extract full hardware metadata from the HTTP header
            meta = json.loads(response.headers.get("X-Camera-Metadata", "{}"))
            exp_ms = meta.get("sensor_exposure_time", 0) / 1e6
            iso = meta.get("sensor_sensitivity")
            timestamp = meta.get("sensor_timestamp")
            gains = meta.get("color_correction_gains", {})

            # Visual servoing / inference logic with true hardware timestamps
            cv2.putText(frame, f"ISO: {iso} | Exp: {exp_ms:.2f}ms | TS: {timestamp}", (30, 50),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)
            cv2.imshow("Robot Arm View", frame)

            if cv2.waitKey(1) & 0xFF == ord('q'):
                break

    except requests.exceptions.RequestException as e:
        print(f"Connection error: {e}")

cv2.destroyAllWindows()
```

---

## Project Structure

```
Cam-App/
├── app/
│   ├── build.gradle.kts           # Module build configuration (NanoHTTPD 2.3.1)
│   └── src/main/
│       ├── AndroidManifest.xml    # Permissions, hardware features, cleartext HTTP
│       ├── java/com/robotics/camera/
│       │   ├── MainActivity.kt    # Headless Activity, WakeLock, HUD
│       │   ├── CameraController.kt# Camera2 hardware controller & ImageReader pipeline
│       │   ├── HttpServer.kt      # NanoHTTPD server (port 8080)
│       │   ├── CameraConfig.kt    # Parameter data model & JSON serialization
│       │   └── NetworkUtils.kt    # Local IPv4 network resolver
│       └── res/
│           ├── layout/
│           │   └── activity_main.xml # TextureView & telemetry overlay layout
│           └── values/
│               ├── colors.xml
│               ├── strings.xml
│               └── themes.xml
├── build.gradle.kts               # Root Gradle build script
├── settings.gradle.kts            # Project repositories & module definitions
└── README.md
```

---

## Building and Installing

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34 (`minSdk = 24`, `targetSdk = 34`)
- JDK 17 or higher
- Android device with Camera2 `FULL` hardware level support

### Build via Gradle
```bash
# Debug APK
./gradlew assembleDebug

# Install to connected device via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Required Permissions
The app requests runtime camera permission upon first launch:
- `android.permission.CAMERA`
- `android.permission.INTERNET`
- `android.permission.ACCESS_WIFI_STATE`
- `android.permission.ACCESS_NETWORK_STATE`
- `android.permission.WAKE_LOCK`

---

## License
Apache 2.0

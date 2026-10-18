import argparse
import json
import sys
import time
import urllib.request
from typing import Any, Dict, List

REQUIRED_METADATA_KEYS = [
    "sensor_exposure_time",
    "sensor_sensitivity",
    "sensor_timestamp",
    "sensor_rolling_shutter_skew",
    "lens_focus_distance",
    "lens_focal_length",
    "lens_aperture",
    "lens_state",
    "lens_intrinsic_calibration",
    "control_ae_mode",
    "control_af_mode",
    "control_awb_mode",
    "color_correction_mode",
    "color_correction_gains",
    "color_correction_transform",
    "lens_optical_stabilization_mode",
    "control_video_stabilization_mode",
    "noise_reduction_mode",
    "edge_mode",
    "color_correction_aberration_mode",
    "scaler_crop_region",
    "jpeg_quality",
]


def fetch_server_status(base_url: str, timeout_seconds: float = 3.0) -> Dict[str, Any]:
    """
    Fetches the live telemetry status from the camera HTTP server.

    Args:
        base_url: Base HTTP URL of the Android camera server (e.g., http://127.0.0.1:8080).
        timeout_seconds: Maximum request wait time in seconds (must be > 0).

    Returns:
        Parsed JSON dictionary returned by GET /status.

    Raises:
        ValueError: If timeout_seconds <= 0 or response body is invalid JSON.
        urllib.error.URLError: If the network request fails.
    """
    if timeout_seconds <= 0:
        raise ValueError("timeout_seconds must be greater than 0")

    url = f"{base_url.rstrip('/')}/status"
    with urllib.request.urlopen(url, timeout=timeout_seconds) as response:
        payload = response.read().decode("utf-8")
        return json.loads(payload)


def capture_single_frame_with_metadata(
    base_url: str,
    timeout_seconds: float = 5.0
) -> Dict[str, Any]:
    """
    Triggers a single GET /capture request and extracts its HTTP metadata and latency.

    Args:
        base_url: Base HTTP URL of the Android camera server.
        timeout_seconds: Maximum request wait time in seconds (must be > 0).

    Returns:
        Dictionary containing 'latency_ms', 'jpeg_size_bytes', and 'metadata'.

    Raises:
        ValueError: If timeout_seconds <= 0 or X-Camera-Metadata header is missing.
        urllib.error.URLError: If the capture request fails.
    """
    if timeout_seconds <= 0:
        raise ValueError("timeout_seconds must be greater than 0")

    url = f"{base_url.rstrip('/')}/capture"
    start_time = time.perf_counter()
    with urllib.request.urlopen(url, timeout=timeout_seconds) as response:
        jpeg_bytes = response.read()
        elapsed_ms = (time.perf_counter() - start_time) * 1000.0
        raw_metadata = response.headers.get("X-Camera-Metadata")

    if not raw_metadata:
        raise ValueError("Missing X-Camera-Metadata header in /capture response")

    return {
        "latency_ms": elapsed_ms,
        "jpeg_size_bytes": len(jpeg_bytes),
        "metadata": json.loads(raw_metadata),
    }


def find_missing_metadata_keys(
    metadata: Dict[str, Any],
    expected_keys: List[str]
) -> List[str]:
    """
    Identifies any required hardware metadata keys missing from a frame metadata payload.

    Args:
        metadata: Parsed X-Camera-Metadata dictionary from a captured frame.
        expected_keys: List of mandatory key names to check.

    Returns:
        List of missing key names (empty list if all keys are present).

    Raises:
        ValueError: If expected_keys is empty.
    """
    if not expected_keys:
        raise ValueError("expected_keys must not be empty")

    return [key for key in expected_keys if key not in metadata]


def run_verification(base_url: str, sample_count: int) -> int:
    """
    Executes a benchmark and metadata verification suite against the Android camera server.

    Args:
        base_url: Base HTTP URL of the Android camera server.
        sample_count: Number of consecutive frames to capture and validate (must be >= 1).

    Returns:
        Exit code (0 on success, 1 on validation or network error).

    Raises:
        ValueError: If sample_count < 1.
    """
    if sample_count < 1:
        raise ValueError("sample_count must be at least 1")

    try:
        status = fetch_server_status(base_url)
        print(f"[OK] Server online: {status.get('device_model')} (Android {status.get('android_version')})")
        print(f"     Active settings: {json.dumps(status.get('current_settings', {}))}")

        latencies: List[float] = []
        for index in range(sample_count):
            sample = capture_single_frame_with_metadata(base_url)
            missing = find_missing_metadata_keys(sample["metadata"], REQUIRED_METADATA_KEYS)
            if missing:
                print(f"[ERROR] Frame {index + 1} missing metadata keys: {missing}", file=sys.stderr)
                return 1

            latencies.append(sample["latency_ms"])
            meta = sample["metadata"]
            print(
                f"[Frame {index + 1}/{sample_count}] "
                f"Size: {sample['jpeg_size_bytes']} B | "
                f"Latency: {sample['latency_ms']:.1f} ms | "
                f"TS: {meta.get('sensor_timestamp')} | "
                f"OIS: {meta.get('lens_optical_stabilization_mode')} | "
                f"EIS: {meta.get('control_video_stabilization_mode')}"
            )

        mean_latency = sum(latencies) / len(latencies)
        print(f"[SUCCESS] Verified {sample_count} frames. Mean latency: {mean_latency:.1f} ms.")
        return 0
    except Exception as exc:
        print(f"[FAILURE] Verification failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Verify Cam-App HTTP endpoints and Camera2 metadata.")
    parser.add_argument("--url", default="http://127.0.0.1:8080", help="Base URL of the camera server")
    parser.add_argument("--samples", type=int, default=3, help="Number of frames to capture")
    args = parser.parse_args()
    sys.exit(run_verification(args.url, args.samples))

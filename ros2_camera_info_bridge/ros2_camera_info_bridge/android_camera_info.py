import requests

import rclpy
from rclpy.node import Node
from sensor_msgs.msg import CameraInfo


class AndroidCameraInfo(Node):
    def __init__(self):
        super().__init__("android_camera_info")
        self.declare_parameter("android_url", "http://127.0.0.1:8080")
        self.declare_parameter("topic", "/camera/camera_info")
        self.declare_parameter("frame_id", "camera")
        self.declare_parameter("period_sec", 1.0)

        self.android_url = self.get_parameter("android_url").value.rstrip("/")
        self.frame_id = self.get_parameter("frame_id").value
        topic = self.get_parameter("topic").value
        period = float(self.get_parameter("period_sec").value)
        self.publisher = self.create_publisher(CameraInfo, topic, 10)
        self.timer = self.create_timer(period, self.publish_camera_info)

    def publish_camera_info(self):
        try:
            response = requests.get(
                f"{self.android_url}/capture?format=json",
                timeout=4.0,
            )
            response.raise_for_status()
            metadata = response.json()["metadata"]
            values = metadata.get("camera_matrix_k", [])
            if len(values) != 9:
                self.get_logger().warning(
                    "Android did not provide a complete camera_matrix_k"
                )
                return

            message = CameraInfo()
            message.header.stamp = self.get_clock().now().to_msg()
            message.header.frame_id = self.frame_id
            message.width = int(metadata["camera_matrix_width"])
            message.height = int(metadata["camera_matrix_height"])
            message.distortion_model = "plumb_bob"
            message.k = [float(value) for value in values]
            message.r = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
            message.p = [
                message.k[0], message.k[1], message.k[2], 0.0,
                message.k[3], message.k[4], message.k[5], 0.0,
                message.k[6], message.k[7], message.k[8], 0.0,
            ]
            self.publisher.publish(message)
        except (requests.RequestException, KeyError, TypeError, ValueError) as error:
            self.get_logger().warning(f"Unable to read Android camera info: {error}")


def main(args=None):
    rclpy.init(args=args)
    node = AndroidCameraInfo()
    try:
        rclpy.spin(node)
    finally:
        node.destroy_node()
        rclpy.shutdown()

import os

from setuptools import setup

package_name = "ros2_camera_info_bridge"

setup(
    name=package_name,
    version="0.1.0",
    packages=[package_name],
    data_files=[
        (
            "share/ament_index/resource_index/packages",
            [os.path.join("resource", package_name)],
        ),
        ("share/" + package_name, ["package.xml"]),
    ],
    install_requires=["setuptools"],
    zip_safe=True,
    description="Publishes Android camera intrinsics as sensor_msgs/CameraInfo.",
    license="Apache-2.0",
    maintainer="android-camera-app",
    maintainer_email="maintainer@example.com",
    entry_points={
        "console_scripts": [
            "android_camera_info = ros2_camera_info_bridge.android_camera_info:main",
        ],
    },
)

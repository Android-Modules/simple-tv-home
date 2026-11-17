# AirPlay 接收器运行库

构建脚本使用 Python 3 从 Android AirPlay Server v0.0.36 的官方 APK 提取 JNI 运行库，并使用固定的 SHA-256 校验下载和缓存。源代码和运行库来源见 [Android AirPlay Server v0.0.36](https://github.com/jqssun/android-airplay-server/releases/tag/v0.0.36) 和 [F-Droid 对应版本源码](https://f-droid.org/packages/io.github.jqssun.airplay/)。许可证见 [LICENSE](LICENSE)。

首次构建下载上游 APK。离线构建可以把 `jqssun-v0.0.36-app-release.apk` 放入构建缓存目录。

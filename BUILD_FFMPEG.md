# Media3 FFmpeg 音频扩展

本项目使用 `org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1`，与 Media3 1.8.0 配套。这组版本支持 Android 5.0（API 21）；版本约束保存在 `gradle/libs.versions.toml`。

该扩展提供音频解码，例如 DTS、TrueHD、AC3、E-AC3、FLAC、ALAC、Vorbis 和 Opus。它**不是 AV1 / HEVC 视频软件解码器，也不是 PGS 字幕渲染扩展**。ExoPlayer 的平台 MediaCodec 优先，FFmpeg 用作音频后备；超出平台视频能力时由 Jellyfin 服务端转为设备可解码的 H.264 / AAC。内置 MPV 使用自己的 libmpv / FFmpeg / dav1d，与此 Media3 音频扩展分开。

一般构建不需要另行编译 FFmpeg：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

如果需要替换音频扩展，应从对应版本的 Jellyfin Media3 扩展源码构建，使用 Android NDK、`ANDROID_PLATFORM=21`，同时提供 `armeabi-v7a` 和 `arm64-v8a`。保持扩展与 Media3 的接口版本一致，遵守其许可证及源代码分发要求。验证 DTS / TrueHD、多音轨、字幕切换和 API 21 真机播放后再替换依赖。

不要直接升级到 Media3 1.9 或更高版本；其最低 SDK 已高于 21。API 21 的 APK、ABI 和签名校验见 `scripts/verify_api21_apks.py`。

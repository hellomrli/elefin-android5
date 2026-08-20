# Elefin Android 5 修正版

基于 [flex36ty/elefin](https://github.com/flex36ty/elefin) 1.1.26 的分支，修复 **Android 5.0 (API 21) 启动闪退**问题。

## 修复内容

1. **启动闪退**（`MainActivity.kt`）：`PackageInfo.longVersionCode` 是 API 28+ 才有的字段，
   在 Android 5.0 上访问会抛 `NoSuchFieldError`（是 `Error`，`catch (Exception)` 接不住）→ 闪退。
   已改为按系统版本判断，API 28 以下走 `versionCode`。
2. **首页崩溃**（`DigitalClock.kt`）：`java.time.LocalTime/DateTimeFormatter` 需要 API 26+，
   且项目未开启 core library desugaring，在 Android 5.0 上渲染首页时钟会崩。
   已改用 `java.util.Date/SimpleDateFormat`。
3. **内置更新检查**指向本仓库（`hellomrli/elefin-android5`），避免被引导去更新回会闪退的原版。

## 构建

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleRelease
```

- 签名：`elefin-android5.keystore`（密码：elefin-android5，请自行保管/更换）
- 输出：`app/build/outputs/apk/release/elefin-release.apk`（含 32 位 armeabi-v7a 与 64 位 arm64-v8a 原生库）

## 许可证

GPL-3.0（继承自上游 Elefin）

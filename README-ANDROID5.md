# Elefin Android 5 修正版

本分支面向 Android TV，最低系统为 Android 5.0（API 21）。当前版本为 `1.2.2-zh-hw`（版本号 `10202`，发布标签 `v1.2.2`）。保留 compileSdk / targetSdk 36、Java 11 字节码、core library desugaring、ARM 分包、原生库 legacy packaging 和 Release R8。

## 本次修复

- MPV 的原生调用通过实例会话串行执行，初始化前、销毁中和旧回调不会访问 native handle。预告片的延迟音轨绑定生命周期，后台等待时不创建播放器。
- ExoPlayer / MPV 共用应用级进度上报队列。退出前获取位置快照，停止上报最多重试三次；失败时保留本地位置，详情与续播读取会恢复尚未上报的位置。转码及 MPV 回退保留当前进度和轨道选择。
- 电视剧库请求 `Series`；搜索和路由保留 `Series` / `Movie` 协议类型，中文仅用于显示。
- 媒体库每页 60 项，接近可见列表末尾时加载下一页。排序、类型、分类与 A–Z 定位在服务端执行；字母定位跳到该字母或之后的条目，`#` 回到库开头。推荐和发现内容分别按行懒加载。合集先加载可见行的预览，通过“查看全部”进入分页网格。
- 取色共用 Coil，只解码 128 px 图片，缓存最多 96 组颜色；首页只在前台刷新，返回时优先同步播放状态，库元数据缓存五分钟。独立推荐请求最多三个并行。
- 首次运行的低内存设备默认简化卡片、720p 背景和减少动画，已保存的设置优先。图片内存预算为 20 / 48 MiB，Exo 媒体缓冲目标为 24 / 64 MiB；这些不是播放器总内存上限。图片磁盘缓存迁到可回收的 `cacheDir`。
- 基于 API 21 `MediaCodecList` / `VideoCapabilities` 检查编解码能力，并在 PlaybackInfo 中提交 DeviceProfile。低内存设备的自动视频回退使用 H.264 / AAC、最高 1080p / 30 fps、10 Mbps、双声道，并受实际解码能力约束。保留自适应码率。
- MPV 默认校验 HTTPS，原生 TLS 使用从 Android 信任库导出的 CA。API 21 的自签名服务器应安装可信 CA 并提供正确证书链；不再全局关闭校验。
- 字幕优先使用系统字体，缺失时复制随包的 DroidSansFallback，覆盖中英文；字体原文件与 Apache 2.0 许可随 APK 分发。
- 时钟按分钟更新并处理时间、时区和前后台变化；隐藏的进度条停止轮询；GL 上一帧纹理仅在启用帧混合时分配。

## 构建与校验

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --console=plain
python3 scripts/verify_api21_apks.py
```

需要 SDK 36、Build Tools 36.0.0 和 NDK 27.0.12077973。Media3 严格固定为 1.8.0，Jellyfin FFmpeg 音频扩展固定为 1.8.0+1；Media3 1.9 需要 API 23，不能直接升级。

输出为两个独立 APK：

- `app/build/outputs/apk/release/elefin-release-armeabi-v7a.apk`
- `app/build/outputs/apk/release/elefin-release-arm64-v8a.apk`

优先选择设备对应的 ABI；不提供 universal APK。无签名配置时生成未签名 Release，可用 `--allow-unsigned` 检查 APK 结构，但不能用它覆盖安装。

## 保留旧签名的更新构建

按本次要求继续使用原发布证书，保留 Android 5 覆盖安装兼容性。应用 ID 和签名身份不变。密钥与密码已从受版本控制的文件中移除；当前工作区的旧密钥保存在忽略目录 `.private-signing/`，`signing.properties` 引用它。请自行备份这两份私有文件，不要提交到 Git。

其他工作区复制 `signing.properties.example` 为 `signing.properties`，填入原密钥配置；也可设置以下环境变量：

| 环境变量 | 内容 |
| --- | --- |
| `ELEFIN_KEYSTORE_FILE` | 原发布密钥文件路径 |
| `ELEFIN_STORE_PASSWORD` | 密钥库密码 |
| `ELEFIN_KEY_ALIAS` | 签名别名 |
| `ELEFIN_KEY_PASSWORD` | 私钥密码 |

Release 工作流需要同名 GitHub Secrets（文件路径除外），以及原密钥内容的 `ELEFIN_KEYSTORE_BASE64` Secret。CI 临时还原文件，构建后删除；构建和校验失败时不发布。PR 校验不需要签名 Secrets。

`scripts/legacy_signing_sha256.txt` 只保存公开证书指纹。校验脚本要求两个 APK 的应用 ID 和版本与项目一致，都是 API 21、包含 v1 签名、匹配原证书，并包含字幕字体和许可。发布时通过 `--release-tag v1.2.2` 同时校验更新版本号；使用 `--apk-dir /path/to/downloads` 可复核下载的云端安装包。标签构建通过后，工作流读取 `releases/<tag>.md` 发布说明并发布两个 APK，同时将 R8 混淆映射保存在构建产物中。

旧密钥已出现在历史提交中；移动文件不能撤销泄露。本次是为兼容更新而保留旧身份，未重写仓库历史。未来换钥需要另行安排迁移，API 21 不支持用现代 v3 签名轮换直接覆盖安装。

## 验证边界

2026-09-06 本地检查：20 项 JVM 测试全部通过；完整 Lint 为 0 错误、213 条警告，无 `NewApi`。两个 Release APK 均通过最低 API 21、ABI、字幕资源、v1 签名和原发布证书指纹校验。警告主要为现有样式、未使用资源和依赖更新提示，不能直接以提高依赖最低 SDK 的方式消除。

本地 JVM 回归测试覆盖原生会话边界、播放位置与协议 JSON、媒体类型、分页、取消和设备回退策略。Lint 和 APK 检查不能替代 API 21 真机验证。请按 `REVIEW-API21.md` 中的场景检查快速返回、断网退出、90% 完成、解码失败后的续播、中英文 SRT / ASS、遥控器焦点，以及大库和长时间播放的 PSS / 帧时间。

当前没有连接 Android 5 真机，未宣称已经测得性能提升比例或完成硬件解码、字幕及 TLS 的端到端验证。

## 许可证

应用继承上游 GPL-3.0。备用字体按 Apache-2.0 分发，来源和完整许可在 `app/src/main/assets/licenses/`。

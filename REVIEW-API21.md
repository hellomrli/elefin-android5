后续修复已实施，变更及最新验证结果见 [README-ANDROID5.md](README-ANDROID5.md)。下文保留提交 `b97e5a7` 修复前的审查记录和原始行号。

审查结论：项目已有 Android 5.0 / API 21 的构建适配，但仍有播放器生命周期、媒体类型判断和低内存使用方面的问题。建议先修复会退出进程、丢失播放状态和导致内容不可用的问题，再优化图片、列表与播放策略。

审查基于提交 `b97e5a7`，日期为 2026-09-06。范围包括构建配置、依赖清单、JNI 库、首页和媒体库、ExoPlayer / MPV、图片加载、音乐及网络层。本次新增审查报告，没有修改业务代码。以下 P1 表示应优先修复的功能或安全问题，P2 表示后续处理的可靠性或性能问题；性能收益需要 API 21 真机测量。

已完成的验证如下。

| 检查 | 结果与边界 |
| --- | --- |
| Gradle | 使用本地 JDK 17 执行 `:app:lintDebug :app:assembleRelease --offline --console=plain` 成功；这是增量构建，89 个任务中执行 10 个。 |
| Android Lint | 0 Error、209 Warning、36 Hint，没有 `NewApi` 报告。报告见 [lint-results-debug.html](/home/lain/codex/elefin-fix/app/build/reports/lint-results-debug.html)。 |
| APK | 两个 Release APK 均声明 minSdk 21 / targetSdk 36，分别只包含 armeabi-v7a、arm64-v8a；以最低 API 21 校验签名通过，均包含 v1 签名。大小约为 17.6 MiB、18.3 MiB。 |
| 依赖 | 检查了本地 AAR Manifest：当前 Core 1.17.0、Media3 1.8.0、TV Compose 和 FFmpeg 扩展均允许 API 21。另行核对的 Media3 1.9.0 已要求 API 23。 |
| 原生库 | `app/src/main/jniLibs` 下两种 ABI 的库均带有 API 21 构建标记；与随包库及 NDK API 21 系统桩库比较，未发现缺失的强导入符号。这不能证明厂商解码器和 GPU 驱动的运行行为。 |
| 运行验证 | ADB 没有连接设备，因此没有测量 API 21 真机的帧率、PSS、首帧时间，也没有执行设备上的播放回归。以下运行结果均按代码路径说明，未声称已经真机复现。 |

优先修改的问题如下。

1. **[P1] MPV 未初始化时暂停 Activity，会直接退出进程。**

   位置：[MpvTvPlayerActivity.kt:174](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/player/mpv/MpvTvPlayerActivity.kt:174)、[创建 View 的条件:850](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/player/mpv/MpvTvPlayerActivity.kt:850)。

   MPV View 要等媒体详情和字幕准备完成才创建，但 `onPause()` 无条件调用 `MPVLib.setPropertyBoolean`。进入 MPV 后，在网络请求尚未完成时按 Home 或返回，就会在 native handle 尚未创建时进入 JNI。检查随包 arm64 `libplayer.so` 的反汇编确认：该路径调用 `die()`，最终执行 `exit(1)`，Kotlin 的 `catch (Exception)` 无法处理。

   建议让所有 JNI 调用受播放器的“未创建 / 已就绪 / 已释放”状态约束；暂停只操作已创建的实例，销毁前取消并协调尚未完成的调用。还应取消 [980 行](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/player/mpv/MpvTvPlayerActivity.kt:980) 没有绑定生命周期的延迟 `audio-add`，否则快速退出预告片后仍可能访问已销毁的 native handle。`MPVLib.isAvailable()` 只表示库已加载，不能替代实例就绪判断。

2. **[P1] MPV 退出时的最终进度上报使用了即将取消的协程作用域。**

   位置：[MpvTvPlayerActivity.kt:567](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/player/mpv/MpvTvPlayerActivity.kt:567)。

   `onDispose` 中通过 `rememberCoroutineScope()` 创建的 `scope.launch` 发送 PlaybackStopped 并标记已观看；该作用域随当前 Composition 销毁而取消，任务无法可靠完成。表现可能是退出后续播位置偏旧、服务端播放会话未及时结束，或观看完成状态未保存。

   建议先保存不可变的会话快照，再交给应用级 Repository 作用域完成有超时、可重试的上报；本地保存最后位置。统一 ExoPlayer 和 MPV 的会话结束逻辑，避免各自使用不同的退出时序。

3. **[P1] 电视剧库把正常的 Series 条目过滤掉了。**

   位置：[TvShowsLibraryScreen.kt:316](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/TvShowsLibraryScreen.kt:316)、[手动刷新:842](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/TvShowsLibraryScreen.kt:842)、[JellyfinApi.kt:1242](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/jellyfin/JellyfinApi.kt:1242)。

   查询设置 `Recursive=false`，正常电视剧库的直接子项是 Series；首次加载及手动刷新却只保留 `Type == "Episode"`，因此“媒体库”页可能为空。设置页返回后的另一条加载路径没有这个过滤，行为也不一致。

   建议电视剧库明确请求 / 保留 Series，统一首次加载、手动刷新和设置变更后的加载方法；Episode 查询用于剧集详情或“接下来播放”。加入包含 Series / Season / Episode 样例的过滤回归测试。

4. **[P1] Jellyseerr 搜索结果把协议类型翻译成中文，导致电视剧跳转到电影接口。**

   位置：[SearchScreen.kt:493](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/SearchScreen.kt:493)、[MainActivity.kt:92](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/MainActivity.kt:92)。

   搜索映射将 `JellyfinItem.Type` 写成“剧集 / 电影”；首页点击路由只有在 Type 为 `Series` 时才传递 `tv`，因此来自 Jellyseerr 的电视剧搜索结果会按 `movie` 打开，可能出现错误详情或 404。

   建议 DTO 保留 `Series` / `Movie`，中文名称只用于显示。将协议类型集中为常量或枚举，并验证搜索结果到详情页的完整路由。

5. **[P1] 发布签名文件与密码一起提交到了仓库。**

   位置：[app/build.gradle.kts:49](/home/lain/codex/elefin-fix/app/build.gradle.kts:49)、[release.yml:47](/home/lain/codex/elefin-fix/.github/workflows/release.yml:47)。签名文件处于 Git 跟踪中。

   能读取这份源码的人可以使用同一发布身份签发 APK。对外分发应使用私有密钥和 CI Secrets，并规划现有安装的迁移；仅删除仓库中的文件不能撤销已经暴露的密钥。API 21 不支持现代 APK v3 签名轮换机制，换钥不能直接视作一次正常覆盖更新，需要评估重装或应用 ID 迁移及数据保留。

6. **[P1] MPV 对所有 HTTPS 视频关闭证书校验。**

   位置：[MPVView.kt:184](/home/lain/codex/elefin-fix/app/src/main/java/is/xyz/mpv/MPVView.kt:184)。

   `tls-verify=no` 对所有地址生效，不局限于注释中的局域网自签名服务器；会失去服务器身份校验，且视频 URL / 请求头携带访问令牌。建议默认校验证书，自签名支持限定到用户明确配置的服务器及其证书 / CA。

   Android 5 已支持 TLS 1.2；证书兼容问题应检查根证书、服务端完整证书链和 native TLS 配置。`networkSecurityConfig` 在 API 21 不生效，也不会替代 MPV 自身的 TLS 设置。

7. **[P2] MPV 字体配置缺少首次安装时的兜底，需要验证文本字幕。**

   位置：[MPVView.kt:132](/home/lain/codex/elefin-fix/app/src/main/java/is/xyz/mpv/MPVView.kt:132)、[字体复制:395](/home/lain/codex/elefin-fix/app/src/main/java/is/xyz/mpv/MPVView.kt:395)。

   代码禁用系统字体提供器，依赖 `assets/fonts`，但源码和两个 Release APK 都没有打包字体。没有字体附件的 SRT / ASS 因此存在缺字或无法显示的风险，不能依赖开发设备遗留的 `filesDir/fonts`。

   建议提供经过 API 21 验证的系统字体回退，或打包有合适许可证且覆盖中文的字体。用清空数据后的首次安装测试中英文 SRT、无字体附件的 ASS，以及带字体附件的 MKV；这一项的实际显示结果仍需真机验证。

8. **[P2] ExoPlayer 中途转码回退会丢失当前播放位置。**

   位置：[JellyfinVideoPlayerScreen.kt:1127](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/JellyfinVideoPlayerScreen.kt:1127)、[续播判断:2094](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/JellyfinVideoPlayerScreen.kt:2094)。

   错误回退先 stop / clear，再设置新媒体源，没有保存和恢复当前进度，并上报位置 0。已经播放过的会话中 `hasSeekedToResume` 为 true，后续 READY 也不会恢复进度。

   建议在停止前保存位置、音轨和字幕选择，切换媒体源后按该快照恢复，并使用一致的会话时间基准上报。验证从中间位置播放后触发解码错误的情形。

性能优化建议按预期影响排序如下。前四项都有直接的代码证据，收益大小仍以真机数据为准。

| 优先级与位置 | 当前问题 | 建议修改 |
| --- | --- | --- |
| P2：[首页取色:597](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/JellyfinHomeScreen.kt:597)、[电影库:532](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/MoviesLibraryScreen.kt:532)、[电视剧库:588](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/TvShowsLibraryScreen.kt:588) | 每次取色新建 ImageLoader，没有设置解码尺寸；先解码大图，再在 [PlexTheme.kt:43](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/ui/PlexTheme.kt:43) 缩成 64×64。独立加载器绕过全局缓存预算，还可能重复下载。单张 RGBA 1080p / 4K 位图约为 7.9 / 31.6 MiB。 | 统一使用 `context.imageLoader`；取色请求在下载和解码阶段就限制到 64–128 px，复用图片鉴权头；按图片 tag 缓存取色结果。对正常背景请求保留显示所需尺寸。 |
| P2：[电影库:1088](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/MoviesLibraryScreen.kt:1088)、[电视剧库:1172](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/TvShowsLibraryScreen.kt:1172) | 推荐页把所有媒体行放在一个 `LazyColumn.item { Column { … } }` 中，外层无法按行懒加载，离屏行也参与组合和测量，并触发各行的图片请求。发现页同样存在这个结构。 | 每个媒体行作为独立的 `item(key, contentType)`，行内条目使用稳定 ID。首页 [1643 行](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/JellyfinHomeScreen.kt:1643) 已有可复用的结构。调整后验证遥控器焦点恢复。 |
| P2：[JellyfinApi.kt:1280](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/jellyfin/JellyfinApi.kt:1280)、[电影库初始化:304](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/MoviesLibraryScreen.kt:304) | `getAllLibraryItems` 虽然按页请求，但会读完整个库后才返回；进入推荐页就启动它。大库造成串行网络等待、全量 DTO 驻留和排序开销。首页还把 `rowCardCount` 作为分页大小，默认 25 会进一步增加往返次数。 | 改成由可见位置驱动的分页，先返回 50–100 项；仅进入媒体库时加载媒体库页。排序、类型和筛选放到服务端，增加稳定的排序规则；字母跳转应定位服务端范围，避免依赖全量内存索引。 |
| P2：[首页首次加载:354](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/JellyfinHomeScreen.kt:354)、[恢复前台:376](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/JellyfinHomeScreen.kt:376)、[定时刷新:410](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/JellyfinHomeScreen.kt:410) | 初次加载和 ON_RESUME 分别触发全量刷新；初次加载还重复调用 `fetchLibraries()`。后台保留 Composition 时，定时刷新循环仍可继续运行，和前台视频播放竞争资源。 | ViewModel / Repository 统一加载入口，对同一刷新请求合并，先展示缓存；返回首页优先刷新播放状态，库元数据设置 TTL。使用 `repeatOnLifecycle` 限定前台工作，独立请求采用有限并发。 |
| P2：[转码参数:873](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/jellyfin/JellyfinApi.kt:873)、[轨道选择:309](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/JellyfinVideoPlayerScreen.kt:309) | 自动转码回退仍允许 3840×2160、默认 40 Mbps / 6 声道，轨道选择又强制最高支持码率。老盒子可能直播放不了，回退后还是超出解码能力或网络吞吐。 | 用 API 21 可用的 `MediaCodecList` / `CodecCapabilities` / `VideoCapabilities` 检查格式、profile、尺寸和帧率，并向 PlaybackInfo 提交能力。限制到设备可解码的 H.264 / AAC 等组合；弱机可从 1080p、8–15 Mbps 起测，保留自适应码率。Media3 FFmpeg 扩展是音频解码器，不能当作 AV1 视频解码兜底。 |

下面这些调整适合在前述问题修复后实施，避免同时改动太多变量。

| 位置 | 建议 |
| --- | --- |
| [DeviceMemory.kt:16](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/util/DeviceMemory.kt:16)、[AppSettings.kt:370](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/jellyfin/AppSettings.kt:370) | 复用已有内存分档，为首次启动的低内存设备默认选择简化卡片、720p 背景和减少动画，并保留用户覆盖。当前图片缓存与视频缓冲已经分档，UI 的低功耗模式仍默认关闭。 |
| [ElefinApplication.kt:24](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/ElefinApplication.kt:24)、[视频缓冲:338](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/JellyfinVideoPlayerScreen.kt:338) | 统一 Java 堆、图片、视频缓冲和 native / GPU 的预算。低档当前为 50 MiB 视频缓冲加大堆的 15% 图片缓存；可实验 16–32 MiB 视频缓冲、16–24 MiB 图片缓存，再按卡顿和 PSS 调整。`targetBufferBytes` 不是播放器总内存上限，标准档 `prioritizeTimeOverSizeThresholds=true` 还可能越过目标字节数。不要把 `largeHeap=true` 当作减少内存占用的措施。 |
| [DigitalClock.kt:31](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/components/DigitalClock.kt:31)、[视频进度:2485](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/JellyfinVideoPlayerScreen.kt:2485)、[首页滚动:1246](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/screens/JellyfinHomeScreen.kt:1246) | 时钟只显示分钟，可对齐分钟更新并处理时间 / 时区变化；进度条在控制层可见时才高频更新，服务端上报和自动续集使用独立的低频任务。用 `snapshotFlow` 收集滚动位置，减少大 Composable 因预加载触发重组；后者已被 Lint 标记。 |
| [GLVideoSurfaceView.kt:369](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/player/GLVideoSurfaceView.kt:369) | 只有启用帧混合时才分配上一帧 FBO；当前只要进入 GL 路径便分配全屏 RGBA 纹理，即使帧混合关闭。1080p 约占 7.9 MiB，4K 约占 31.6 MiB。GL 对象的创建和释放继续在 GL 线程执行。 |
| [JellyfinMusicApi.kt:76](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/music/data/JellyfinMusicApi.kt:76)、[JellyfinAuthService.kt:45](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/jellyfin/JellyfinAuthService.kt:45)、[JellyseerrApiService.kt:186](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/jellyseerr/JellyseerrApiService.kt:186) | 将主 Jellyfin API 已使用的共享客户端模式扩展到音乐等模块，或明确由所有者关闭客户端。音乐页面会分别创建服务实例；Jellyseerr 虽提供 close，但页面调用方没有释放。共享传输层时仍按请求提供鉴权，避免跨用户状态混用。 |
| [ElefinApplication.kt:36](/home/lain/codex/elefin-fix/app/src/main/java/com/flex/elefin/ElefinApplication.kt:36) | 图片磁盘缓存放在 `filesDir`，系统清理缓存时无法回收它。建议使用 `cacheDir` 并按设备可用空间设置预算，提供明确的清理入口；设置缓存尺寸时同时考虑 Coil 自身的上下限，不能仅按注释中的“5% 可用空间”理解。 |

在 API 21 框架内，建议保留现有 `minSdk=21`、core library desugaring、原生库 legacy packaging、ABI 分包和 Release R8。`compileSdk=36` / `targetSdk=36` 本身不妨碍在 Android 5 上运行，没必要为此降到 21；脱糖仅解决支持范围内的 Java API，Android framework 新 API 仍需版本判断或兼容层。

依赖升级必须核对实际解析版本和 AAR 的最低 SDK，尤其应将 Media3 与对应 FFmpeg 扩展约束在已验证支持 API 21 的组合。本次确认 1.8.0 可用、1.9.0 的 AAR 要求 API 23，不能直接接受 Lint 的“升级到最新版”建议。CI 可增加完整 Lint、APK minSdk / v1 签名检查，以及媒体类型和播放状态转换的回归测试。

结构调整应优先抽取两个实际共享的模块：播放器会话管理负责初始化、回退、进度快照和销毁；媒体行数据模型负责分页、刷新和焦点 ID。这样可以统一上述重复逻辑，并逐步缩小目前约 5,800 行的播放器 Composable 和多个媒体库页面的修改范围。

建议用 API 21 的 1 GiB / armeabi-v7a 盒子作为最紧约束，再用另一种 SoC / ABI 复核。性能测量使用 Release APK，工具优先选择 API 21 可用的 `dumpsys meminfo`、`dumpsys gfxinfo`、atrace / systrace，以及 ExoPlayer AnalyticsListener。`FrameMetrics`、硬件位图、`RenderEffect` 等新 API 不能作为 API 21 的实现前提，Baseline Profiles 也不会在 API 21 上提供对应的运行优化。

| 验证场景 | 记录和通过条件 |
| --- | --- |
| MPV 慢网、字幕等待、预告片进入后立即退出 | 多次按 Home / 返回，进程不能因 JNI 调用退出；离开后没有延迟命令访问旧实例。 |
| 退出与播放回退 | 中间位置退出、90% 后退出、触发转码回退；核对续播位置、已观看状态、音轨 / 字幕与服务端会话。 |
| 新装字幕与媒体类型 | 清空数据后测中文 SRT / ASS；电视剧库应显示 Series，Jellyseerr 电视剧搜索应进入 tv 详情。 |
| 大媒体库与快速方向键滚动 | 分别使用约 1,000 / 10,000 条目的库，记录首屏时间、请求数量、PSS / GC、长帧和焦点恢复；首屏工作量应与可见条目数量相关。 |
| 视频与弱网 | H.264 1080p、HEVC Main / Main10、4K、AV1、DTS / TrueHD、字幕切换；记录首帧、丢帧、重缓冲和实际选用的解码器，确认超出能力时转码到可播放规格。 |
| 前后台及长时间播放 | 连续进出详情和播放器、返回首页、休眠恢复及至少 30 分钟播放；检查 PSS / 线程数是否持续增长，后台是否仍刷新整库。 |

实施顺序建议为：MPV 生命周期与进度 → 媒体类型 / 路由 → 签名和 TLS → 取色与按行懒加载 → 分页及刷新合并 → 播放能力协商和内存调参。每一阶段保留前后测量，避免用未测量的百分比描述性能提升。

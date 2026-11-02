# 识谱助手：项目记忆与接手说明

更新：2026-11-02。用户最新要求是“先回退到上次版本，然后加入项目记忆文件，交给其他 agent 处理”。本轮已停止修复并完成回退。

## 当前有效状态

- 项目：`D:\desktop\AI\识谱助手`，原生 Android Java + 安装包内 WebView 页面，无 Gradle，包名 `com.shipu.assistant`。
- 有效源码版本 **0.3.0 / versionCode 5**，对应 2026-10-30 的交付状态。保留已工作的后台识别功能。本轮 0.3.1 的横屏、播放、谱表补检尝试均已撤销。
- 上一版实际存在的 APK：`build/outputs/识谱助手-0.3.0-测试.apk`，188,028,467 字节。SHA256：`163bb9bc4c081fe1699bff7630d7c8eb72eed899dff6b33a9b164c5fc5f06dc4`。这是允许调试的测试包，不能描述为已验证的正式发布包。
- 普通构建脚本默认输出 `识谱助手-0.3.0.apk`，这个名称的文件当前没有现成产物；需要构建才会生成。
- 回退仅作用于电脑项目，没有连接手机降级、安装、删除乐谱或修改手机数据。
- 项目没有 `.git`，不能使用 `git reset` 找回历史。本次通过旧 APK 恢复资源，并精确反向应用本轮原生源码补丁，恢复生成脚本和版本号。

## 2026-11-02 修复轮次（源码已改，真机未验证）

- 横屏练习页布局：`app/src/main/assets/refinements.css` 恢复 flex 链条并压缩钢琴区高度。
- 按键/自动播放无声：`PianoAudio.java` 去掉主线程 `CountDownLatch`（回调跑在主 looper，会自锁），改为异步加载计数。
- 后台识谱中断：`LocalInferenceRpc.java` 单次调用时限 180s→600s，失败时回传中文错误包；`omr-engine.js` 页超时 20 分钟；`RecognitionService.java` 增加 `describe()` 可读提示。
- 纯钢琴谱被当成"多个乐器声部"：已核实 homr 按整页每个谱位输出 `<part>`，上谱表记作 `Voice`(midi 54)、下谱表才记作 `Piano`(midi 1)，旧逻辑因此只保留 1 个 part 并丢掉上谱表。`MusicXmlParser.java` 现识别 homr 这种 `Piano`/`Voice` 组合并合并全部 part（按小节序号对齐，跳过 `attributes`，冲突谱号重映射），警告改为"已自动合并同一页的上下谱表。"；`app.js` 双谱表按上=高音、下=低音选谱号。
- 最新测试包 `build/outputs/识谱助手-0.3.0-fix4.apk`，188,032,563 字节，SHA256 `95BB6ABEC4FFDA1ABCD2DC844A3C109B73CF2FBB33C56FC04962094F2CC05F7E`，v2/v3 签名通过，99 条 ZIP 路径已规范化，mp3 未压缩。以上均只做编译、单元级合并验证与静态检查，**没有真机测试**；已识别过的旧乐谱需要重新识别才能补回被丢掉的上谱表。

## 回退与归档证据

- `handoff/2026-11-02/rollback-verification.json`：回退核对结果。
- 全部 **95 个 APK assets** 与上一版安装包逐字节一致；运行 `build_worker.py` 再生成后仍一致。本地模型清单校验通过。
- 回退源码 Java 编译通过；音乐逻辑 117 项、原生推理通信 45 项、后台引擎 JS 生命周期契约 14 项通过；UI 测试脚本语法检查通过。
- 这些检查用于确认回退完整，没有重新做真机音效、横屏或后台实测，也没有重新运行完整模型。用户提出的缺陷仍应视为待解决。
- 未发布尝试源码及相关证据：`handoff/2026-11-02/reverted-attempt-source.zip`，80 个轻量文件，含原生、页面、生成脚本、测试和识别结果；不含模型权重、签名密钥或 APK。文件清单和哈希见同目录 `checkpoint-manifest.json`。它是回退前快照，**不是可直接合并的补丁**。
- 本轮 0.3.1 测试 APK 和 `.build` 中的旧编译产物已清理，避免误发。既有 0.3.0 测试 APK 保留。

## 用户要做的产品

安卓识谱练习应用：导入图片、PDF 等乐谱，在虚拟钢琴上按原谱顺序练音高，弹对后推进。可提前选左手、右手、双手，支持单音练习与小节练习，按键有钢琴声，支持和弦。首先照顾竖屏单手操作，也需要横屏及键盘缩放以展示更多键位。识谱后可手动校对。

用户最终要求模型在手机内运行，不依赖电脑服务；此前允许联网的回答已被后续离线要求取代。新版已删除旧远程识别入口和服务，不能恢复。

原先八项需求已写入 0.3.0，但仍有实际缺陷：多页预览及顺序、自动播放、删除项目、移除电脑服务、单音模式只展示一个音符、小节当前位置黄色提示、后台识别、缩放和横屏。不要因已有按钮或测试就宣称全部可用。

## 最新确认与待修问题

1. **后台识别：用户最新明确说已解决。** 保留实现，不再次重构它。先前报错或卡住属于旧反馈。
2. **横屏看不见音符：未解决。** 用户截图中底部键盘/操作区占满可用高度，谱面被裁切。不能只测 852×393 的名义窗口，要扣除系统栏后的可用高度，如 852×300、740×280、640×240。检查音符真实绘制范围、所有裁切祖先及是否被遮挡，保留竖屏体验。
3. **乐谱自动播放：用户反馈像未实现，未完成真机确认。** 现有 `PlaybackTimeline`、播放按钮和 SoundPool 调度并非空白功能，但可见入口、样本加载、实际声音和播放游标仍需核对。界面 mock 的播放状态不能证明真机有声。
4. **识别丢失开头/顺序异常及多声部警告：未解决。** 最新截图是 `for_river`，显示“检测到多个乐器声部，当前仅导入优先选中的钢琴声部”，原图却是独奏钢琴双谱表。不能通过隐藏警告或盲目合并所有乐器声部来掩盖问题。

## 识别问题定位线索（仅研究结果，未应用）

用户 PDF 已复制为 `references/for_river.pdf`（2 页、90,222 字节），原路径是 `D:\wechat\history\xwechat_files\wxid_bufm5e3ra7gz22_bcdd\msg\file\2026-10\for_river.pdf`。

- 人工查看原谱：第一页 24 小节，第二页 27 小节（原编号 25–51），总共 51 小节。第一页开头两组四小节音乐相同，不能仅凭首音判断是不是从开头开始。
- 旧版真实模型输出 `build/verification/service-river-page-1.musicxml` 仅 20 小节，第二页 27 小节。第一页确实少了 4 小节。
- `homr` 中五线谱是 `Staff`，合并后的钢琴上下谱表可能已是十条线的一个 `Staff`，`is_grandstaff=True`。`MultiStaff.staffs` 表示并行乐器，不等于连续的钢琴谱行。
- 实际诊断：第一页首组只检测到高音谱表，漏掉低音五线谱；其余五组是十线 grand staff。旧 `staff_parsing._ensure_same_number_of_staffs` 的周期启发式把首组丢掉，因此从第 5 小节开始。
- 根源应在 OMR 分组/谱表检测层处理；泛用 `MusicXmlParser` 优先选钢琴声部的逻辑不能无条件改成合并所有声部，避免破坏真实合奏谱。
- 本轮被撤销的尝试包含 `piano_grouping.py`：根据实际分割 mask 中的五条等距长谱线补检漏掉的 Staff，再交回原音符检测；按页面纵向整理钢琴系统，遇到无法配对的谱表明确失败而非静默丢行。保留十线 Staff，不生成猜测的音高。
- 该尝试的 21 个小测试曾通过；真实生产模型两页运行完成，输出 **24 + 27 小节，各 1 个 part**，约 137 秒、42 次本地 RPC、0 外部请求。输出及日志 `build/verification/piano-recovered-river-*` 也收入归档。**只证明恢复小节数量，音高、时值、谱号、升降号、连线及节奏没有逐音人工认证，没有合并后的全练习验证，没有真机验证。用户要求回退，不能把这当作已交付修复。**
- 模型漏检诊断见 `build/verification/piano-layout-debug-runtime.log`；生成脚本改动和 helper 都在归档内。当前源码已不包含这些实验处理。
- 下次更换识别算法要更新 `RecognitionService` 的缓存 fingerprint，避免复用旧错误 XML；当前基线是 `3:`。已有保存乐谱不会自动补回丢失音符，应允许用户重新识别并注意手工校对内容。

## 横屏与播放的撤销尝试（供参考）

归档的 `refinements.css` 改为按实际高度分配谱面、工具条和键盘。归档 UI 测试曾在 8 个尺寸检查音符椭圆的真实范围、祖先裁切、点击命中及播放按钮，生成 13 张截图。该浏览器结果不是手机实测，回退后也不再代表当前布局。

归档原生播放尝试包含音色加载等待、loading/error 状态、媒体音量用途与音频焦点、非零 SoundPool stream 计数、实际发声音符高亮。当前 `MainActivity`、`PianoAudio` 都已恢复原逻辑。不能只恢复 UI 却忽略 native/bridge 状态契约，也不能声称已听到真机声音。

## 关键代码与数据流

- `app/src/main/java/com/shipu/assistant/MainActivity.java`：WebView 桥接、练习、预览请求和原生播放调度。
- `RecognitionService.java`：前台服务持有独立 headless WebView、partial wake lock、进度通知、逐页检查点；不依赖练习页面保持打开。用户确认它已可后台运行。
- `LocalAssetHost.java` / `LocalInferenceRpc.java`：APK 本地资源与二进制推理通信；实际类名可按目录核对。通信块 512 KiB，上限 64 MiB，避免把张量转成巨大 JSON。
- `app/src/main/assets/omr-engine.html` / `omr-engine.js`：服务内识别页面与逐页编排。
- `app/src/main/assets/local-omr/`：homr、Pyodide、ONNX Runtime、三份模型、Python runner、独立 Python/ORT worker。
- `tools/mobile-omr/build_worker.py` 与 `templates/`：生成 runtime 的源头。修改生成文件时也应修改源头，否则再生成会覆盖。
- `music/MusicXmlParser.java`：优先选钢琴 part；`ScorePageMerger.java`：按物理页合并，记录 sourcePage/sourceMeasure，继承调号、节拍、速度。
- `PracticeEngine.java`：NOTE 单音推进，MEASURE 事件/和弦推进，左右手按 staff 分配；`PlaybackTimeline.java`：休止、连音、速度变化及播放时间线。
- `PianoAudio.java`：SoundPool，15 个 MP3 样本，移调覆盖琴键。样本必须不压缩以供 `AssetManager.openFd` 使用。
- `ScoreJson.java` / `LibraryRepository.java`：本机乐谱和校对持久化。删除需要用户在界面确认。
- 前端入口 `index.html` / `app.js` / `refinements.css`；`mobile-omr.js` 管理识别 UI。新版不应重新增加服务器地址、token 或远程识别设置。

## 离线实现与包装注意事项

本地虚拟 HTTPS origin 为 `https://appassets.androidplatform.net/`，只映射安装包资源，不是云端服务。Python 与 ORT 分别在 worker 中运行，通过 Android 本地同步 XHR / 二进制 RPC 交换推理结果。标准 CPU/WASM 单线程，不要求 WebAssembly JSPI、SharedArrayBuffer 或跨源隔离。

三份模型共 157,482,318 字节，runtime 约 244.5 MiB，APK 约 188 MB。模型、字体和音色均随安装包提供。

Windows AAPT2 曾将嵌套资源写成反斜杠 ZIP 路径，导致手机无法加载模型。**保留 `tools/normalize_apk_paths.py`，在 align/sign 前执行；审计必须同时检查 ZipInfo.orig_filename 和 local header。** 签名证书 SHA256：`f42f6ae64f77fa4eac93d4950193a60ed6f09b8983646e144dabb385642a4bfc`。沿用项目已有 `.toolchain/debug.keystore` 才能覆盖安装；不要输出或传递私钥。

## 构建、测试与设备

PowerShell 工作目录是项目根目录。工具已在本机，无需重新安装开发环境：

- Java 17：`D:\AI-Toolchain\jdk-17.0.20.1+1\bin`。
- Android SDK 工具：项目 `.toolchain/`；ADB：`.toolchain/adb/platform-tools/adb.exe`。
- Python：`C:\Users\10575\AppData\Local\Programs\Python\Python312\python.exe`；需要 numpy / PDF 库时使用 `C:\Users\10575\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe`。
- Node：`D:\DevTools\nodejs\node.exe`；Playwright 位于 `C:/Users/10575/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright`。

常用命令：

```powershell
& tools/build-android.ps1
& tools/build-android.ps1 -Debuggable -ApkName '识谱助手-0.3.0-测试.apk'
& tests/music/run.ps1
& tests/native/run.ps1
& 'D:\DevTools\nodejs\node.exe' tests/native/service_engine_test.cjs
& 'D:\DevTools\nodejs\node.exe' tests/ui/run.cjs
& 'C:\Users\10575\AppData\Local\Programs\Python\Python312\python.exe' -X utf8 tools/mobile-omr/verify.py
& 'C:\Users\10575\AppData\Local\Programs\Python\Python312\python.exe' -X utf8 tests/audit_apk.py build/outputs/识谱助手-0.3.0-测试.apk
```

`tests/native/package_report.py` 默认检查普通非调试产物，不能拿调试包运行后误判程序有问题。`tests/native/service_runtime.cjs` 是真实 Python/ONNX 模型验证，但 Android I/O 边界由电脑浏览器模拟，不能称为真机或完整后台测试；耗时约 2 分钟，两页可用 `SHIPU_TEST_PREFIX` 区分输出。用户要求修改完再统一调试，不要在需求未改完时反复让用户试安装。

用户手机：Xiaomi 15，12 GB 物理 RAM（截图的 +6 GB 是扩展内存），Android 15，旧 WebView 曾为 Chrome 131。另有旧红米已授权 USB 调试，曾成功安装和运行，ADB serial `ac9953d3`。2026-11-02 最后一次检测没有在线设备。已获授权可检查和操作测试手机，但不要绕过 MIUI 的 USB 安装权限、登录或 SIM 限制；此前正常文件管理器手动安装可行。出现 shell 无安装权限时不要重复强装或修改系统权限。

## 接手建议

先基于当前 0.3.0 建立源码快照，再按用户下一条指示处理识别、横屏和播放。需要利用归档时先解压到单独目录并对照，不覆盖整个项目。以这份 PDF 的 24+27 小节、开头双谱表、跨页顺序、左右手练习遍历及原速播放为回归样例；最后再做手机实际显示、声音与后台验证。不要把旧成功日志当作新代码的验证。

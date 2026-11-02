# 识谱助手：项目记忆与接手说明

更新：2026-11-02（晚）。项目已纳入 Git 并推送到 `https://github.com/bedouyu/piano.git`；本轮恢复了“钢琴谱表补齐”修复并重新出包 fix5。真机仍未验证。

## 当前有效状态

- 项目：`D:\desktop\AI\识谱助手`，原生 Android Java + 安装包内 WebView 页面，无 Gradle，包名 `com.shipu.assistant`。
- 版本 **0.3.0 / versionCode 5**。Git 仓库 `main` 跟踪 `origin/main`，首个提交 `270b511`。
- 仓库只提交源码：`.gitignore` 排除 `build/`、`handoff/`、`.build/`、`.toolchain/`、`tools/mobile-omr/downloads/`、`assets/local-omr/{models,pyodide,ort,homr.zip,sources}`、`__pycache__`。这些由 `tools/mobile-omr/prepare.py` 重新生成。
- 最新包 `build/outputs/识谱助手-0.3.0-fix6.apk`，188,036,746 字节，SHA256 `5D0193E97AF4FD0AB9BBD21D542771F9B5EF0943FA25E4466D80521EF25A9CCA`，v2/v3 签名通过，ZIP 路径已规范化，mp3 未压缩。只做编译与浏览器内真实模型验证，**没有真机测试**。
- 上一版 fix5：188,036,746 字节，SHA256 `75FCD2A7...`（真机第 1 页失败，见下节）。
- 上一版测试包 `识谱助手-0.3.0-测试.apk`（188,028,467 字节，SHA256 `163bb9bc...`）仍是可调试备份。


## 2026-11-02 修复轮次（源码已改，真机未验证）

- 横屏练习页布局：`app/src/main/assets/refinements.css` 恢复 flex 链条并压缩钢琴区高度。
- 按键/自动播放无声：`PianoAudio.java` 去掉主线程 `CountDownLatch`（回调跑在主 looper，会自锁），改为异步加载计数。
- 后台识谱中断：`LocalInferenceRpc.java` 单次调用时限 180s→600s，失败时回传中文错误包；`omr-engine.js` 页超时 20 分钟；`RecognitionService.java` 增加 `describe()` 可读提示。
- 纯钢琴谱被当成"多个乐器声部"：已核实 homr 按整页每个谱位输出 `<part>`，上谱表记作 `Voice`(midi 54)、下谱表才记作 `Piano`(midi 1)，旧逻辑因此只保留 1 个 part 并丢掉上谱表。`MusicXmlParser.java` 现识别 homr 这种 `Piano`/`Voice` 组合并合并全部 part（按小节序号对齐，跳过 `attributes`，冲突谱号重映射），警告改为"已自动合并同一页的上下谱表。"；`app.js` 双谱表按上=高音、下=低音选谱号。
- 最新测试包 `build/outputs/识谱助手-0.3.0-fix4.apk`，188,032,563 字节，SHA256 `95BB6ABE...`（已被 fix5 取代）。

## 2026-11-02 恢复轮次：钢琴谱表补齐（已应用，真机未验证）

- 用户反馈“识别多页 PDF 后前面几行不见了”。核实结论：与页数无关，是第 1 页首个谱行只检出高音谱表，homr 的 `_ensure_same_number_of_staffs` 按整页周期裁掉了这个不合群的谱表，整行消失（`Removing the first 1 staff(s)...`）。
- 已按用户确认恢复归档方案：`app/src/main/assets/local-omr/python/piano_grouping.py`（源自 `tools/mobile-omr/templates/piano_grouping.py`）在 `runner.py` 中接管 `staff_parsing._ensure_same_number_of_staffs`、`homr.main.detect_staff` 和 `staff_parsing.parse_staff_image`，用分割 mask 的等距长谱线补回漏检谱表，并按页面纵向重新整理钢琴系统。
- 与归档版本的差异：`recognise()` 发现 part 数不等于 1 时**不再抛错**，只写入 `piano-layout-unresolved` 事件，多 part 仍交给 `MusicXmlParser` 合并，避免真实合奏谱无法导入。
- 验证（浏览器内跑真实生产模型，输入 `build/verification/river/page-1-2200.png`、page-2）：第 1 页 **24 小节 / 6 行**、第 2 页 **27 小节 / 6 行**，各 1 个 part，`externalRequests` 为空，`service_runtime.cjs` 判定 `passed: true`。输出 `build/verification/restored-river-page-{1,2}.musicxml`。**只核对小节与谱行数量，音高、时值、谱号、升降号仍未逐音人工认证。**
- `RecognitionService` 检查点 fingerprint 由 `3:` 改为 `4:`，旧缓存会失效，已识别乐谱重新识别时不会复用错误 XML（fix6 又升到 `5:`）。
- 不能用“能识别出 24 小节”推断音准正确；模型本身仍会误读（例：第 1 小节左手把持续和弦展开成 4 个二分音符）。

## 2026-11-02 fix6：真机第 1 页失败的修正

- 真机诊断（Android 15 / WebView 131）显示：第 1 页第 1 组 10 线正常，但**第 2 组只检出 5 线**（top 768–832.5，36 个音符），其低音谱表整体漏检；恢复函数当时要求连续 5 条等距长线，而该组下方只有 4 条（897/913/929/945），因此没有补回。随后 `normalize_piano_staffs` 遇到未配对谱表**直接抛错**，整页识别失败。
- 改动：`recover_five_line_staffs` 增加“4 条等距长线 + 外推第 5 条”的补检（`_band_near` 在该位置附近找实际墨迹，找不到才用外推值），仍然检查宽度、间距一致性与不重叠。
- `normalize_piano_staffs` 未配对时**不再抛错**，保留该单谱表作为一个谱行并记录 `piano-layout-unpaired / kept-single`，避免整页失败；代价是该谱行的低音缺失，需要人工校对。
- 回归测试 `tests/native/piano_grouping_test.py`（3 例）：复现真机几何可以补回伙伴谱表并合成为 6 个谱行；完整页面不会凭空造谱表；未配对时保留而不抛错。
- 桌面真实模型重跑（`restored2-river-*`）仍为第 1 页 24 小节 / 第 2 页 27 小节，`passed: true`，零外部请求；说明放宽补检没有污染正常页面。
- fingerprint 升到 `5:`，旧检查点全部失效。
- 用户真机装 fix6 后回复“修好了”：本次“整页识别失败/前面几行不见”按已解决处理。音准仍需校对，横屏与播放声音未在本轮确认。

## 2026-11-02 fix7/fix8：开屏页与界面简化（未真机验证）

- 开屏欢迎页：新增 `assets/splash/cat.jpg`（由用户提供的戴耳机白猫图裁出左侧 760×1080 竖构图，106 KB），`index.html` 里 `#splash` 按裁剪比例 `object-fit:contain` 显示，3.6 秒后淡出，点击或按回车/空格立即进入乐谱库；横屏改为左图右文。
- 底部导航从「乐谱 / 练习 / 记录」改为「乐谱 / 设置」：练习改为打开乐谱后进入（详情页“开始练习”与练习设置弹窗不变）。
- 删除 `#history` 屏幕与 `renderHistory()`（练习记录入口按要求去掉），`state.history` 仍由原生返回但不再渲染。
- 原“设置”底部弹窗删除，改为独立 `#settings` 屏幕：显示琴键音名、手机离线识谱说明与运行信息、关于。`mobile-omr.js` 注入目标从 `settings-sheet` 改为 `settings` 的 `.page-scroll`。
- 仅做 JS 语法检查与构建，**未真机验证**；设置页布局与开屏在不同屏幕比例下的效果需要装包确认。

## 2026-11-02 fix9：练习页专注（全屏练习）按钮

- 练习页乐谱面板右上角新增 `.focus-toggle`（“全屏练习” / “退出专注”），点击给 `#practice` 加 `focus` 类：隐藏页头、进度、反馈条、示范播放条、上一个/提示/重来、谱面图例，以及键盘上方的音域行、缩放行、八度行和提示文字，只留五线谱与琴键；再点一次或离开练习页自动还原（`showScreen` 统一重置）。
- 专注模式下琴键区固定 150px（横屏 96px），乐谱区吃掉剩余高度；已验证 393×852 下页头/反馈为 `display:none`、琴键 150px、乐谱区约 640px。
- `tests/ui/run.cjs` 已同步：练习记录页的步骤改为设置页，并新增专注模式断言。**测试套件首个断言在竖屏基线提交上就失败**（示范播放条需滚动才能让出位置：bar bottom 488 > 工具条 top 469，`playback-bar>span{flex-basis:100%}` 占第二行），与本改动无关，尚未修复。

## 2026-11-02 fix10：校对页改为谱面点选编辑

- 校对区不再显示“音名输入框 + 谱表下拉 + 删除”的文本行，改为按小节直接画五线谱：`drawEditorStaff()` 复用练习页的音高定位（`notePosition`），显示谱号、加线、临时记号、延音弧，单谱表 143 / 双谱表 198 高，小节间可「‹ 第 N 小节 ›」切换。
- 点音符（`data-action="pickNote"`，椭圆上带 event/note 序号）选中后出现操作条：♭ 半音、♯ 半音、低八度、高八度、换谱表、删除；`adjustEditorNote()` 直接改 draft 的 midi/name/staff，删除后清空的事件会自动移除，音高裁剪在 A0–C8。
- 由此删除不再可达的文本解析路径：`parsePitch()` 整个移除，`collectEditor()` 只保留标题同步。`tests/music/editor_test.js` 重写为用例覆盖半音/八度/换谱表/删除/上限裁剪/标题回退，已通过。
- 浏览器实测（393×852，真实 Edge 无头）：渲染 2 谱表 5 个音符，点选 A5 后 ♭ → G♯5、换谱表 → 下谱表；`renderEditor` 不再产生 `.editor-row`。**未真机验证**。

## 2026-11-02 fix11：过滤退化谱表，修复 cv2.resize 崩溃

- 真机导入《Merry Christmas Mr. Lawrence 精编还原》（5 页）在第 2 页失败：`piano-layout` 把一条 20px 宽的竖条（重复小节线块，left 1810 / right 1830，lines 10）当成谱表系统，另有 240px 宽的残片；homr 裁出该竖条后 `center_image_on_canvas` 里 `cv2.resize` 断言 `inv_scale_x > 0` 失败，整页中止。
- `piano_grouping.normalize_piano_staffs` 增加宽度过滤：以系统中位宽度为基准，宽度 < `max(80px, 35% 中位宽)` 的谱表直接丢弃并记录 `piano-layout-dropped`（若全部都很窄则不过滤）。真实页面实测丢弃的是 20px 与 240px 两条，得到 5 个正常系统。
- `runner._checked_piano_staff_image` 再加一道兜底：裁切区域宽 < 80 或高 < 20 时跳过该行（`piano-system-skipped`）而不是抛错，避免单行异常再毁掉整页。
- 检查点 fingerprint `5:` → `6:`。
- 桌面真实模型回归（`SHIPU_INPUT_IMAGES` 指向该 PDF 的 2200px PNG，5 页全跑）：`passed: true`，304 秒，各页 1 个 part，小节数 6 / 22 / 26 / 23 / 16，音符 96 / 277 / 476 / 752 / 224，零外部请求。**手机端仍未实测**，音准仍需校对。


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
4. **识别丢失开头 / 多声部警告 / 真机整页失败：源码已修，待真机重识别确认。** 谱表补齐恢复后第 1 页回到 24 小节；fix6 又处理了真机“第 2 组低音谱表漏检 + 未配对即抛错”的情况。已保存的乐谱必须重新识别（fingerprint 已升到 `5:`）；单谱表兜底会缺低音，需校对。不要把“小节数对了”当成识别正确。

## 识别问题定位线索（已应用谱表补齐，音准仍待校对）

用户 PDF 已复制为 `references/for_river.pdf`（2 页、90,222 字节），原路径是 `D:\wechat\history\xwechat_files\wxid_bufm5e3ra7gz22_bcdd\msg\file\2026-10\for_river.pdf`。

- 人工查看原谱：第一页 24 小节，第二页 27 小节（原编号 25–51），总共 51 小节。第一页开头两组四小节音乐相同，不能仅凭首音判断是不是从开头开始。
- 旧版真实模型输出 `build/verification/service-river-page-1.musicxml` 仅 20 小节，第二页 27 小节。第一页确实少了 4 小节。
- `homr` 中五线谱是 `Staff`，合并后的钢琴上下谱表可能已是十条线的一个 `Staff`，`is_grandstaff=True`。`MultiStaff.staffs` 表示并行乐器，不等于连续的钢琴谱行。
- 实际诊断：第一页首组只检测到高音谱表，漏掉低音五线谱；其余五组是十线 grand staff。旧 `staff_parsing._ensure_same_number_of_staffs` 的周期启发式把首组丢掉，因此从第 5 小节开始。
- 根源应在 OMR 分组/谱表检测层处理；泛用 `MusicXmlParser` 优先选钢琴声部的逻辑不能无条件改成合并所有声部，避免破坏真实合奏谱。
- 本轮已撤销后又按用户确认恢复的 `piano_grouping.py`：根据实际分割 mask 中的五条等距长谱线补检漏掉的 Staff，再交回原音符检测；按页面纵向整理钢琴系统，遇到无法配对的谱表记录事件而非静默丢行。保留十线 Staff，不生成猜测的音高。
- 原归档记录：21 个小测试通过；真实生产模型两页输出 **24 + 27 小节，各 1 个 part**，约 137 秒、42 次本地 RPC、0 外部请求。`build/verification/piano-recovered-river-*` 为归档证据，`restored-river-*` 为本次恢复后重跑证据。**只证明恢复小节数量，音高、时值、谱号、升降号、连线及节奏没有逐音人工认证，没有合并后的全练习验证，没有真机验证。**
- 模型漏检诊断见 `build/verification/piano-layout-debug-runtime.log`。
- 更换识别算法时要更新 `RecognitionService` 的缓存 fingerprint，避免复用旧错误 XML；当前基线已由 `3:` 改为 `4:`。已有保存乐谱不会自动补回丢失音符，fingerprint 变更后重新识别会重跑整份乐谱。

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

# 项目交接入口

开始工作前先读根目录的 [PROJECT_MEMORY.md](PROJECT_MEMORY.md)。它记录当前有效版本、用户约束、待解决问题、构建方式和验证边界。

- 当前源码为 **0.3.0 / versionCode 5**，已纳入 Git 并推送到 `https://github.com/bedouyu/piano.git`；仓库只含源码，模型与构建产物由 `tools/mobile-omr/prepare.py` 重新生成。
- 2026-11-02 的 0.3.1 尝试仅在 `handoff/2026-11-02/reverted-attempt-source.zip` 留档；其中“钢琴谱表补齐”部分已按用户确认恢复进源码，其余部分不要自行重新应用。

- 用户已确认后台识别能正常工作，保留现有 `RecognitionService` 与独立识别 WebView 的实现。识别必须在手机本地运行，已删除的远程电脑服务不要恢复。
- 保留竖屏单手操作，同时处理横屏；修改完需求后再统一调试，这是用户明确提出的工作顺序。
- 修改前保存可恢复的源码快照；记录实际测试结果，不把浏览器模拟或静态检查写成真机验证。改了识别算法要同时提升 `RecognitionService` 的检查点 fingerprint。
- APK 中嵌套资源必须使用正斜杠。保留构建中的 `normalize_apk_paths.py`，并在签名前执行；钢琴 MP3 必须保持不压缩。
- 不覆盖或删除用户手机中的乐谱。已有识别结果需要重新识别才能补回漏掉的音符，注意保留用户手工校对。


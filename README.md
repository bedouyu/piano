# 识谱助手

帮助初学者把五线谱上的音符对应到钢琴键位的安卓应用。中文界面，支持竖屏单手操作与横屏更多键位。

## 安装与使用

新版安装包：`build/outputs/识谱助手-0.3.0.apk`。同一项目签名，可覆盖旧版保留本机乐谱和练习记录。安装方法见[部署到手机](部署到手机.md)。

导入图片、PDF或MusicXML/MXL → 手机离线识谱 → 校对 → 选择左右手与小节 → 练习或示范播放。

- PDF按原始页码预览、逐页识别，小节保留来源页；切换乐谱不会复用旧页或旧练习范围。
- 本机后台识别，通过前台服务通知显示进度；可切换应用或锁屏，取消或进程中断后再次开始会复用已完成页。
- 逐音模式仅显示当前一个音符；小节模式以黄色标示当前位置，和弦可依次点齐。
- 示范播放支持所选左右手、小节范围、暂停、停止与速度调整，保留休止和延音，不计入练习成绩。
- 88键音域，键宽18–64像素可调、独立音域滑条与八度切换；支持横屏。
- 乐谱列表及详情提供删除操作，经确认后删除该曲原文件和识别结果。
- 内置钢琴采样、原谱放大、手动音符校对与本机练习记录。

电脑识谱服务、连接文件、服务地址、访问令牌和相关证书已移除；全部识谱资源随APK提供。

## 构建与验证

原生Java负责导入、PDF渲染、持久化、前台服务与SoundPool钢琴；WebView显示离线界面。独立服务WebView运行Python及ONNX工作线程，不随练习页面退出而终止。模型和运行组件来自APK，资源拦截拒绝外部主机。

```powershell
.\tools\build-android.ps1
.\tests\music\run.ps1
.\tests\native\run.ps1
node tests/music/editor_test.js
node tests/native/service_engine_test.cjs
node tests/ui/mobile_test.cjs
python tests/native/apk_paths_test.py
python tools/mobile-omr/verify.py
python tests/audit_apk.py
python tests/native/package_report.py
```

最低Android7/API24，目标/编译API35。构建时统一Windows ZIP子目录分隔符后再签名，审计核对原始中央目录和文件头。模型资源准备见[工具说明](tools/mobile-omr/README.md)。

## 范围与边界

自动识谱结果需校对，复杂和弦、声部、装饰音、调号和连线可能出错。练习谱面按音高重绘，不复刻原谱排版；反复跳房暂不展开。示范按已识别的小节顺序播放，错误识别会影响声音与顺序。系统强行结束应用会中断后台计算，已完成页会保留；不承诺强制停止后持续运行。

原版0.2.2曾在红米M2012K11AC完成真实识谱；新版验证结果单独记录，不能沿用旧版真机结论。

- [产品需求](docs/产品需求.md)
- [离线识谱实现](docs/手机离线识谱.md)
- [第三方材料与署名](THIRD_PARTY_NOTICES.md)

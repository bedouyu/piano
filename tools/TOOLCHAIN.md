# Android 构建工具

本项目使用原生 Java 和 Android 系统 API，不需要 Gradle 或第三方安卓库。

## 构建

在 PowerShell 运行：

```powershell
.\tools\setup-android.ps1
.\tools\build-android.ps1
```

默认输出 `build/outputs/识谱助手-0.3.0.apk`（0.3.0），最低 Android 7 / API 24，目标 Android 15 / API 35。使用 `-Debuggable -ApkName '识谱助手-真机测试版.apk'` 可生成用于USB真机排查的版本；普通包关闭调试入口。

构建会在签名前运行 `normalize_apk_paths.py`，把Windows工具产生的ZIP子目录名称统一为安卓可识别的正斜线路径，再对齐并签名。最终审计检查中央目录与本地文件头的原始名称，避免Windows自动规范路径掩盖打包错误。
使用 JDK 17；脚本优先使用本机已有 JDK，或 `JAVA_HOME` 指定的 JDK。
项目的调试证书在 `.toolchain/debug.keystore`；这是仅用于本地测试的标准调试签名。

## 官方来源与下载校验

- 元数据：https://dl.google.com/android/repository/repository2-1.xml
- Android Platform 35 revision 2：https://dl.google.com/android/repository/platform-35_r02.zip
  - 官方 SHA-1：`0bb560a90a7a2cbd0dd8348224d518b638fe7949`
- Android Build-Tools 35.0.0 Windows：https://dl.google.com/android/repository/build-tools_r35_windows.zip
  - 官方 SHA-1：`af059bb67cf7786f45ee0db85e2d24985df1b4b6`
- Android Platform-Tools 37.0.1 Windows：https://dl.google.com/android/repository/platform-tools_r37.0.1-win.zip
  - 官方 SHA-1：`e03e78b1d80b396f1c3358e31251cb31740e1110`
  - `adb` 路径：`.toolchain/adb/platform-tools/adb.exe`

工具下载、解压均保存在本项目 `.toolchain/` 内，不修改全局开发环境。
脚本不执行 `sdkmanager --licenses`，也不生成 SDK 许可接受记录。
工具附带的许可文件仍保留在下载包和解压目录中；使用与分发应遵守其条款。

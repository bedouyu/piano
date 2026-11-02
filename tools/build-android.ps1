param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$VersionName = '0.3.0',
    [int]$VersionCode = 5,
    [int]$MinSdk = 24,
    [int]$TargetSdk = 35,
    [string]$ApkName = '识谱助手-0.3.0.apk',
    [switch]$Debuggable,
    [string]$ToolchainRoot = ''
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
$toolchain = if ($ToolchainRoot) { [IO.Path]::GetFullPath($ToolchainRoot) } else { Join-Path $ProjectRoot '.toolchain' }
$buildTools = Join-Path $toolchain 'build-tools\android-15'
$androidJar = Join-Path $toolchain 'platform\android-35\android.jar'
$javaHomeForBuild = 'D:\AI-Toolchain\jdk-17.0.20.1+1'
if (-not (Test-Path -LiteralPath (Join-Path $javaHomeForBuild 'bin\javac.exe'))) {
    $javaHomeForBuild = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe -ErrorAction Stop).Source) }
}
$javac = Join-Path $javaHomeForBuild 'bin\javac.exe'
$jar = Join-Path $javaHomeForBuild 'bin\jar.exe'
$keytool = Join-Path $javaHomeForBuild 'bin\keytool.exe'
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$d8 = Join-Path $buildTools 'd8.bat'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$pythonForBuild = 'C:\Users\10575\AppData\Local\Programs\Python\Python312\python.exe'
if (-not (Test-Path -LiteralPath $pythonForBuild)) { $pythonForBuild = (Get-Command python.exe -ErrorAction Stop).Source }
foreach ($required in @($androidJar, $javac, $jar, $keytool, $aapt2, $zipalign, $d8, $apksigner)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "缺少构建文件：$required。请先运行 tools\setup-android.ps1。" }
}

$main = Join-Path $ProjectRoot 'app\src\main'
$manifest = Join-Path $main 'AndroidManifest.xml'
if (-not (Test-Path -LiteralPath $manifest)) { throw "未找到 AndroidManifest.xml：$manifest" }
$build = Join-Path $ProjectRoot '.build'
$classes = Join-Path $build 'classes'
$generated = Join-Path $build 'generated'
$dex = Join-Path $build 'dex'
$output = Join-Path $ProjectRoot 'build\outputs'
# Only remove known generated folders inside this exact project.
foreach ($generatedDirectory in @($classes, $generated, $dex)) {
    $absoluteGeneratedDirectory = [IO.Path]::GetFullPath($generatedDirectory)
    if (-not $absoluteGeneratedDirectory.StartsWith($ProjectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw '构建清理目录不在项目内。' }
    if (Test-Path -LiteralPath $absoluteGeneratedDirectory) { Remove-Item -LiteralPath $absoluteGeneratedDirectory -Recurse -Force }
    New-Item -ItemType Directory -Path $absoluteGeneratedDirectory -Force | Out-Null
}
New-Item -ItemType Directory -Path $output -Force | Out-Null

function Invoke-BuildTool {
    param([string]$Executable, [string[]]$Arguments)
    Push-Location -LiteralPath $ProjectRoot
    try {
        & $Executable @Arguments
        if ($LASTEXITCODE -ne 0) { throw "构建工具失败：$Executable（退出码 $LASTEXITCODE）" }
    } finally { Pop-Location }
}

function Get-ProjectRelativePath {
    param([string]$AbsolutePath)
    return [IO.Path]::GetRelativePath($ProjectRoot, $AbsolutePath)
}

$compiledResources = Join-Path $build 'resources.zip'
$baseApk = Join-Path $build 'unsigned.apk'
$alignedApk = Join-Path $build 'aligned.apk'
$normalizedApk = Join-Path $build 'normalized.apk'
foreach ($generatedFile in @($compiledResources, $baseApk, $alignedApk)) {
    if (Test-Path -LiteralPath $generatedFile) { Remove-Item -LiteralPath $generatedFile -Force }
}
Write-Host '1/6 编译应用资源'
# AAPT2's Windows entry point mishandles absolute non-ASCII paths. Relative
# ASCII paths keep projects in Chinese-named folders fully supported.
Invoke-BuildTool $aapt2 @('compile', '--dir', (Get-ProjectRelativePath (Join-Path $main 'res')), '-o', (Get-ProjectRelativePath $compiledResources))
$linkArguments = @('link', '-o', (Get-ProjectRelativePath $baseApk), '--manifest', (Get-ProjectRelativePath $manifest), '-I', (Get-ProjectRelativePath $androidJar), '--java', (Get-ProjectRelativePath $generated), '--auto-add-overlay', '-R', (Get-ProjectRelativePath $compiledResources), '-0', 'mp3', '--min-sdk-version', "$MinSdk", '--target-sdk-version', "$TargetSdk", '--version-code', "$VersionCode", '--version-name', $VersionName)
if ($Debuggable) { $linkArguments += '--debug-mode' }
if (Test-Path -LiteralPath (Join-Path $main 'assets')) { $linkArguments += @('-A', (Get-ProjectRelativePath (Join-Path $main 'assets'))) }
Invoke-BuildTool $aapt2 $linkArguments

Write-Host '2/6 编译 Java 源码'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $main 'java'), $generated -Filter '*.java' -File -Recurse | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
if ($sources.Count -eq 0) { throw '没有 Java 源码。' }
$sourceList = Join-Path $build 'sources.txt'
[IO.File]::WriteAllLines($sourceList, $sources, [Text.UTF8Encoding]::new($false))
# --release 8 provides the Java 8 compiler bootstrap used for lambda bytecode;
# D8 subsequently desugars it against the Android library and selected min API.
Invoke-BuildTool $javac @('-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '--release', '8', '-classpath', $androidJar, '-d', $classes, ('@' + $sourceList))
$classesJar = Join-Path $build 'classes.jar'
if (Test-Path -LiteralPath $classesJar) { Remove-Item -LiteralPath $classesJar -Force }
Invoke-BuildTool $jar @('cf', $classesJar, '-C', $classes, '.')

Write-Host '3/6 转换 Android 字节码'
$originalJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $javaHomeForBuild
    Invoke-BuildTool $d8 @('--lib', $androidJar, '--min-api', "$MinSdk", '--output', $dex, $classesJar)
    $dexFiles = @(Get-ChildItem -LiteralPath $dex -Filter '*.dex' -File)
    if ($dexFiles.Count -eq 0) { throw 'D8 未产生 classes.dex。' }
    foreach ($dexFile in $dexFiles) { Invoke-BuildTool $jar @('uf', $baseApk, '-C', $dex, $dexFile.Name) }

    Write-Host '4/6 规范安卓资源路径并对齐 APK'
    Invoke-BuildTool $pythonForBuild @('-X', 'utf8', (Join-Path $PSScriptRoot 'normalize_apk_paths.py'), $baseApk, $normalizedApk)
    Invoke-BuildTool $zipalign @('-f', '-p', '4', (Get-ProjectRelativePath $normalizedApk), (Get-ProjectRelativePath $alignedApk))

    Write-Host '5/6 使用项目调试证书签名'
    $keystore = Join-Path $toolchain 'debug.keystore'
    if (-not (Test-Path -LiteralPath $keystore)) {
        Invoke-BuildTool $keytool @('-genkeypair', '-keystore', $keystore, '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Android Debug,O=Android,C=US')
    }
    $apk = Join-Path $output $ApkName
    $signedApk = Join-Path $build 'signed.apk'
    Invoke-BuildTool $apksigner @('sign', '--ks', $keystore, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', (Get-ProjectRelativePath $signedApk), (Get-ProjectRelativePath $alignedApk))

    Write-Host '6/6 验证签名与安装元数据'
    Invoke-BuildTool $apksigner @('verify', '--verbose', (Get-ProjectRelativePath $signedApk))
    Invoke-BuildTool $aapt2 @('dump', 'badging', (Get-ProjectRelativePath $signedApk))
    Copy-Item -LiteralPath $signedApk -Destination $apk -Force
    $apkInfo = Get-Item -LiteralPath $apk
    Write-Host ("APK: {0} ({1} bytes)" -f $apkInfo.FullName, $apkInfo.Length)
    Write-Host ('SHA256: ' + (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash)
} finally {
    $env:JAVA_HOME = $originalJavaHome
}

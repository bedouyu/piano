param([string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot))

$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
$toolchain = Join-Path $ProjectRoot '.toolchain'
$downloads = Join-Path $toolchain 'downloads'
New-Item -ItemType Directory -Path $downloads -Force | Out-Null
# Google Android repository metadata, stable API 35 / Build-Tools 35.0.0.
# This script does not run sdkmanager --licenses or create acceptance records.
$packages = @(
    @{ File = 'platform-35_r02.zip'; SHA1 = '0bb560a90a7a2cbd0dd8348224d518b638fe7949'; Size = 64273788; Destination = 'platform'; Required = 'android-35\android.jar' },
    @{ File = 'build-tools_r35_windows.zip'; SHA1 = 'af059bb67cf7786f45ee0db85e2d24985df1b4b6'; Size = 59878107; Destination = 'build-tools'; Required = 'android-15\aapt2.exe' },
    @{ File = 'platform-tools_r37.0.1-win.zip'; SHA1 = 'e03e78b1d80b396f1c3358e31251cb31740e1110'; Size = 8044989; Destination = 'adb'; Required = 'platform-tools\adb.exe' }
)
foreach ($package in $packages) {
    $archive = Join-Path $downloads $package.File
    $downloadNeeded = (-not (Test-Path -LiteralPath $archive)) -or ((Get-Item -LiteralPath $archive).Length -lt $package.Size)
    if ($downloadNeeded) {
        $officialUrl = 'https://dl.google.com/android/repository/' + $package.File
        if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
            & curl.exe --fail --location --continue-at - --connect-timeout 30 --max-time 1800 --retry 2 --silent --show-error --output $archive $officialUrl
            if ($LASTEXITCODE -ne 0) { throw "Google 工具下载失败，可重跑此脚本续传：$officialUrl" }
        } else {
            Invoke-WebRequest -Uri $officialUrl -OutFile $archive -TimeoutSec 1800
        }
    }
    $actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA1).Hash
    if ($actualHash -ne $package.SHA1) { throw "Google 下载包校验失败：$archive" }
    $destination = Join-Path $toolchain $package.Destination
    if (-not (Test-Path -LiteralPath (Join-Path $destination $package.Required))) {
        Expand-Archive -LiteralPath $archive -DestinationPath $destination -Force
    }
}
Write-Host 'Android API 35 与 Build-Tools 35 已准备就绪。'
Get-ChildItem -LiteralPath $toolchain | Select-Object Name, Mode

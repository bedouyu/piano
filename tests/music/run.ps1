$ErrorActionPreference = 'Stop'
$taskRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$classesPath = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force -Path $classesPath | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'app\src\main\java\com\shipu\assistant\music') -Filter '*.java' | ForEach-Object FullName)
$sources += Join-Path $PSScriptRoot 'MusicDomainTest.java'
& javac -encoding UTF-8 -source 8 -target 8 -d $classesPath @sources
if ($LASTEXITCODE -ne 0) { throw 'Music-domain compilation failed' }
& java -cp $classesPath MusicDomainTest
if ($LASTEXITCODE -ne 0) { throw 'Music-domain tests failed' }

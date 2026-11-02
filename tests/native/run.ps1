$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$java = 'D:\AI-Toolchain\jdk-17.0.20.1+1\bin'
$output = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Path $output -Force | Out-Null
& (Join-Path $java 'javac.exe') -encoding UTF-8 --release 8 -d $output (Join-Path $project 'app\src\main\java\com\shipu\assistant\LocalInferenceRpc.java') (Join-Path $PSScriptRoot 'LocalInferenceRpcTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Native RPC test compilation failed' }
& (Join-Path $java 'java.exe') '-Dfile.encoding=UTF-8' -cp $output LocalInferenceRpcTest
if ($LASTEXITCODE -ne 0) { throw 'Native RPC tests failed' }

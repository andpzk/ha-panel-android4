param([string] $ToolRoot = (Join-Path $PSScriptRoot 'work'))

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Run([string] $Program, [string[]] $Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program exited with code $LASTEXITCODE" }
}

$jdk = Join-Path $ToolRoot 'jdk17/jdk-17.0.21+9/bin'
$tools = Join-Path $ToolRoot 'sdk/build-tools/35.0.0'
$androidJar = Join-Path $ToolRoot 'sdk/platforms/android-16/android.jar'
$build = Join-Path $PWD 'work/build'
$apk = Join-Path $PWD 'outputs/ha-panel.apk'
$key = Join-Path $PWD 'work/build-signing.p12'

New-Item -ItemType Directory -Force $build, (Split-Path $apk) | Out-Null
if (!(Test-Path -LiteralPath $key)) {
    Run "$jdk/keytool.exe" @('-genkeypair', '-keystore', $key, '-storetype', 'PKCS12',
        '-storepass', 'android', '-alias', 'panel', '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '3650', '-dname', 'CN=Home Panel Local Build')
}
$source = @(Get-ChildItem src -Recurse -Filter '*.java' | ForEach-Object FullName)
$videoLibraries = @(Get-ChildItem (Join-Path $ToolRoot 'video-libs') -Filter '*.jar' |
    Where-Object Name -NotLike 'exoplayer-hls-*' | ForEach-Object FullName)
$classpath = (@($androidJar) + $videoLibraries) -join [IO.Path]::PathSeparator
$classes = Join-Path $build 'classes.jar'
$base = Join-Path $build 'base.apk'
$aligned = Join-Path $build 'aligned.apk'
New-Item -ItemType Directory -Force "$build/classes" | Out-Null
Run "$jdk/javac.exe" (@('-encoding', 'UTF-8', '-source', '8', '-target', '8',
    '-Xlint:-options', '-classpath', $classpath, '-d', "$build/classes") + $source)
Run "$jdk/jar.exe" @("-J-Djava.io.tmpdir=$build", 'cf', $classes, '-C', "$build/classes", '.')
Run "$jdk/java.exe" (@('-cp', "$tools/lib/d8.jar", 'com.android.tools.r8.D8',
    '--min-api', '16', '--lib', $androidJar, '--output', $build, $classes) + $videoLibraries)
Run "$tools/aapt.exe" @('package', '-f', '-M', 'AndroidManifest.xml', '-S', 'res', '-I', $androidJar, '-F', $base)
Run "$jdk/jar.exe" @("-J-Djava.io.tmpdir=$build", 'uf', $base, '-C', $build, 'classes.dex')
Run "$tools/zipalign.exe" @('-f', '4', $base, $aligned)
Run "$jdk/java.exe" @('-jar', "$tools/lib/apksigner.jar", 'sign', '--min-sdk-version', '16',
    '--ks', $key, '--ks-key-alias', 'panel', '--ks-pass', 'pass:android', '--out', $apk, $aligned)
Run "$jdk/java.exe" @('-jar', "$tools/lib/apksigner.jar", 'verify', '--verbose', '--min-sdk-version', '16', $apk)
Get-Item -LiteralPath $apk | Select-Object Name, Length

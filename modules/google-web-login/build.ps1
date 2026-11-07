param(
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [Parameter(Mandatory=$true)][string]$Jdk,
    [Parameter(Mandatory=$true)][string]$XposedApiJar,
    [Parameter(Mandatory=$true)][string]$Keystore,
    [Parameter(Mandatory=$true)][string]$PasswordFile,
    [Parameter(Mandatory=$true)][string]$OutputApk
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$build = Join-Path $root 'build'
$platform = Join-Path $AndroidSdk 'platforms/android-36/android.jar'
$tools = Join-Path $AndroidSdk 'build-tools/37.0.0'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
$OutputApk = [IO.Path]::GetFullPath($OutputApk)
$outputDirectory = Split-Path -Parent $OutputApk
New-Item -ItemType Directory -Force -Path $classes,$dex,$outputDirectory | Out-Null
$expectedBuildRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build'))
foreach ($target in @($classes, $dex)) {
    $resolvedTarget = (Resolve-Path -LiteralPath $target).Path
    if (-not $resolvedTarget.StartsWith($expectedBuildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to clean a path outside the module build directory'
    }
}
Get-ChildItem -LiteralPath $classes -Force | Remove-Item -Recurse -Force
Get-ChildItem -LiteralPath $dex -Force | Remove-Item -Recurse -Force
& (Join-Path $Jdk 'bin/javac.exe') -encoding UTF-8 -source 8 -target 8 -Xlint:-options -classpath "$platform;$XposedApiJar" -d $classes (Join-Path $root 'LoginHook.java')
if ($LASTEXITCODE -ne 0) { throw 'Google module Java compile failed' }
$classFiles = @(Get-ChildItem -LiteralPath $classes -Filter '*.class' -Recurse -File | Select-Object -ExpandProperty FullName)
& (Join-Path $Jdk 'bin/java.exe') -cp (Join-Path $tools 'lib/d8.jar') com.android.tools.r8.D8 --lib $platform --lib $XposedApiJar --min-api 28 --output $dex @classFiles
if ($LASTEXITCODE -ne 0) { throw 'Google module DEX compile failed' }
$unsigned = Join-Path $build 'google-web-login-unsigned.apk'
& (Join-Path $tools 'aapt2.exe') link -I $platform -A (Join-Path $root 'assets') --manifest (Join-Path $root 'AndroidManifest.xml') -o $unsigned
if ($LASTEXITCODE -ne 0) { throw 'Google module manifest packaging failed' }
& (Join-Path $Jdk 'bin/jar.exe') uf $unsigned -C $dex classes.dex
& (Join-Path $tools 'zipalign.exe') -f -p 4 $unsigned (Join-Path $build 'google-web-login-aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'Google module alignment failed' }
& (Join-Path $Jdk 'bin/java.exe') -jar (Join-Path $tools 'lib/apksigner.jar') sign --ks $Keystore --ks-key-alias sim-test --ks-pass "file:$PasswordFile" --out $OutputApk (Join-Path $build 'google-web-login-aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'Google module APK signing failed' }
& (Join-Path $Jdk 'bin/java.exe') -jar (Join-Path $tools 'lib/apksigner.jar') verify $OutputApk
if ($LASTEXITCODE -ne 0) { throw 'Google module signature verification failed' }

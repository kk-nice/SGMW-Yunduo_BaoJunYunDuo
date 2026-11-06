# Builds PedestrianWarningOff.apk for Android 9 (API 28) without Gradle.
# Windows port of build.sh: javac -> d8 -> aapt2 -> zipalign -> apksigner.
$ErrorActionPreference = 'Stop'

$HERE = Split-Path -Parent $MyInvocation.MyCommand.Path
$ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:USERPROFILE 'Android\Sdk' }
$BT = Join-Path $ANDROID_HOME 'build-tools\35.0.0'
$ANDROID_JAR = Join-Path $ANDROID_HOME 'platforms\android-28\android.jar'
$OUT = Join-Path $HERE 'build'
$KEYSTORE = Join-Path $HERE 'release.keystore'
$APK = Join-Path $HERE 'PedestrianWarningOff-Yunduo.apk'

if (-not (Test-Path -LiteralPath $ANDROID_JAR)) { throw "android.jar not found: $ANDROID_JAR (run: sdkmanager 'platforms;android-28')" }
if (-not (Test-Path -LiteralPath (Join-Path $BT 'aapt2.exe'))) { throw "build-tools 35.0.0 not found under $BT" }

# Resolve JDK tools (jar/keytool) -- javapath shims hide the real JDK bin.
$jdkBin = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin'
    if (Test-Path -LiteralPath (Join-Path $candidate 'jar.exe')) { $jdkBin = $candidate }
}
if (-not $jdkBin) {
    foreach ($c in @(Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending)) {
        if (Test-Path -LiteralPath (Join-Path $c.FullName 'bin\jar.exe')) { $jdkBin = Join-Path $c.FullName 'bin'; break }
    }
}
if (-not $jdkBin) { throw 'JDK not found: jar.exe not located (set JAVA_HOME or install a JDK under C:\Program Files\Java)' }
$JAR = Join-Path $jdkBin 'jar.exe'
$KEYTOOL = Join-Path $jdkBin 'keytool.exe'
$JAVAC = Join-Path $jdkBin 'javac.exe'
if (-not (Test-Path -LiteralPath $JAVAC)) { throw "javac not found: $JAVAC" }

if (Test-Path -LiteralPath $OUT) { Remove-Item -Recurse -Force -LiteralPath $OUT }
New-Item -ItemType Directory -Force -Path (Join-Path $OUT 'classes'), (Join-Path $OUT 'dex') | Out-Null

# 1) javac -> .class
$srcs = @(Get-ChildItem -Recurse -LiteralPath (Join-Path $HERE 'src') -Filter '*.java' | ForEach-Object { $_.FullName })
if ($srcs.Count -eq 0) { throw 'no java sources found under src/' }
& $JAVAC -source 11 -target 11 -encoding UTF-8 -Xlint:-options -classpath $ANDROID_JAR -d (Join-Path $OUT 'classes') @srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

# 2) d8 -> classes.dex (min API 28 = Android 9)
$env:JAVA_HOME = Split-Path -Parent $jdkBin
$classes = @(Get-ChildItem -Recurse -LiteralPath (Join-Path $OUT 'classes') -Filter '*.class' | ForEach-Object { $_.FullName })
& (Join-Path $BT 'd8.bat') --min-api 28 --lib $ANDROID_JAR --output (Join-Path $OUT 'dex') @classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed with exit code $LASTEXITCODE" }

# 3) aapt2 link -> unsigned.apk
& (Join-Path $BT 'aapt2.exe') link -o (Join-Path $OUT 'unsigned.apk') -I $ANDROID_JAR --manifest (Join-Path $HERE 'AndroidManifest.xml')
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed with exit code $LASTEXITCODE" }

# 4) pack classes.dex into the apk (jar updates the zip archive)
& $JAR uf (Join-Path $OUT 'unsigned.apk') -C (Join-Path $OUT 'dex') .
if ($LASTEXITCODE -ne 0) { throw "jar update failed with exit code $LASTEXITCODE" }

# 5) zipalign
& (Join-Path $BT 'zipalign.exe') -f -p 4 (Join-Path $OUT 'unsigned.apk') (Join-Path $OUT 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw "zipalign failed with exit code $LASTEXITCODE" }

# 6) sign (generate keystore on first run)
if (-not (Test-Path -LiteralPath $KEYSTORE)) {
    & $KEYTOOL -genkeypair -keystore $KEYSTORE -alias pwoff -keyalg RSA -keysize 2048 -validity 36500 -storepass pwoffpass -keypass pwoffpass -dname 'CN=PedestrianWarningOff'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}
& (Join-Path $BT 'apksigner.bat') sign --ks $KEYSTORE --ks-key-alias pwoff --ks-pass pass:pwoffpass --key-pass pass:pwoffpass --out $APK (Join-Path $OUT 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw "apksigner failed with exit code $LASTEXITCODE" }

Write-Output "Built $APK"

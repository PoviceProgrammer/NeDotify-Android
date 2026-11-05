param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$AvdHome = (Join-Path $env:USERPROFILE '.android/avd'),
    [string]$AvdName = 'NeDotify',
    [string]$ApkPath,
    [string]$ApplicationId = 'ru.nedotify.app.debug',
    [switch]$CheckOnly
)
$ErrorActionPreference='Stop'
if(-not $SdkPath){$SdkPath=Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
$emulator=Join-Path $SdkPath 'emulator/emulator.exe'
$adb=Join-Path $SdkPath 'platform-tools/adb.exe'
if(-not (Test-Path -LiteralPath $emulator)){throw 'Set -SdkPath to the installed Android SDK.'}
$acceleration=& $emulator -accel-check 2>&1
if($LASTEXITCODE -ne 0){
    $acceleration | Write-Output
    throw 'Windows Hypervisor Platform is not available. Enable it with enable-emulator.ps1 as administrator and restart Windows.'
}
$acceleration | Write-Output
if($CheckOnly){exit 0}
if(-not $ApkPath -or -not(Test-Path -LiteralPath $ApkPath)){
    throw 'Set -ApkPath to an explicitly selected APK after packaging is authorized. verify.ps1 checks sources only and does not create an APK.'
}
$apk=(Resolve-Path -LiteralPath $ApkPath).Path
if(-not(Test-Path -LiteralPath (Join-Path $AvdHome "$AvdName.ini"))){throw 'The prepared NeDotify AVD is missing. Set -AvdHome/-AvdName to your existing AVD.'}
$savedAvdHome=$env:ANDROID_AVD_HOME
$savedSdk=$env:ANDROID_SDK_ROOT
try {
    $env:ANDROID_AVD_HOME=$AvdHome;$env:ANDROID_SDK_ROOT=$SdkPath
    $serial='emulator-5580'
    $devices=& $adb devices
    if(-not($devices -match '^emulator-5580\s+')) {
        $config=Join-Path $AvdHome "$AvdName.avd/config.ini"
        if(Test-Path -LiteralPath $config) {
            $lines=Get-Content -LiteralPath $config
            $lines=$lines | Where-Object {$_ -notmatch '^hw.audioOutput='}
            @($lines;'hw.audioOutput=yes') | Set-Content -LiteralPath $config -Encoding ascii
        }
        # This window is intentionally visible: the user tests the Android UI with a mouse.
        Start-Process -FilePath $emulator -ArgumentList @('-avd',$AvdName,'-port','5580','-no-snapshot','-gpu','swiftshader','-no-boot-anim','-prop','qemu.hw.mainkeys=0')
    }
    $deadline=[DateTime]::UtcNow.AddMinutes(4)
    do {
        $booted=(& $adb -s $serial shell getprop sys.boot_completed 2>$null) -join ''
        if($booted.Trim() -eq '1'){break}
        if([DateTime]::UtcNow -gt $deadline){throw 'Android did not boot in four minutes. Emulator is left open for inspection.'}
        Start-Sleep -Seconds 2
    } while($true)
    & $adb -s $serial install -r $apk
    if($LASTEXITCODE -ne 0){throw 'APK installation failed.'}
    & $adb -s $serial shell am start -n "$ApplicationId/ru.nedotify.app.MainActivity"
    if($LASTEXITCODE -ne 0){throw 'Android app launch failed.'}
    Write-Output 'NeDotify is open in the Android Emulator. Downloads, sound and offline mode can now be tested.'
} finally {
    if($null -eq $savedAvdHome){Remove-Item Env:ANDROID_AVD_HOME -ErrorAction SilentlyContinue}else{$env:ANDROID_AVD_HOME=$savedAvdHome}
    if($null -eq $savedSdk){Remove-Item Env:ANDROID_SDK_ROOT -ErrorAction SilentlyContinue}else{$env:ANDROID_SDK_ROOT=$savedSdk}
}

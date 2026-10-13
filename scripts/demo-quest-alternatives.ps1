param(
    [ValidateSet('live', 'rain', 'clear', 'night')]
    [string]$Scenario = 'live',
    [string]$DeviceId = 'emulator-5554'
)
$ErrorActionPreference = 'Stop'
$alternativeAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $alternativeAdb)) { throw 'No se encontro Android SDK/platform-tools/adb.exe.' }
& $alternativeAdb -s $DeviceId get-state
if ($LASTEXITCODE -ne 0) { throw 'Abre el emulador en Android Studio antes de ejecutar el escenario.' }
& $alternativeAdb -s $DeviceId shell am force-stop com.sidequests.app
if ($LASTEXITCODE -ne 0) { throw 'No se pudo detener la app para cambiar el escenario.' }
$alternativeArgs = @('-s', $DeviceId, 'shell', 'am', 'start', '-n', 'com.sidequests.app/.MainActivity')
if ($Scenario -ne 'live') {
    $alternativeWeather = if ($Scenario -eq 'rain') { 'RAIN' } else { 'CLEAR' }
    $alternativeHour = if ($Scenario -eq 'night') { '23' } else { '14' }
    $alternativeArgs += @('--es', 'alternative_demo_weather', $alternativeWeather, '--ei', 'alternative_demo_hour', $alternativeHour)
}
& $alternativeAdb @alternativeArgs
if ($LASTEXITCODE -ne 0) { throw 'No se pudo abrir la app. Instala primero la version debug desde Android Studio.' }
Write-Host "Escenario: $Scenario. Acepta una mision y usa Exit > motivo > Start another quest instead."
Write-Host 'Los escenarios rain/clear/night muestran una etiqueta de contexto simulado en la nueva pantalla.'

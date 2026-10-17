# Launches the packaged Windows app on the CI runner, takes screenshots and
# collects startup timings from its log. Run from the repository root with pwsh.
$ErrorActionPreference = 'Continue'
$root = (Get-Location).Path
$out = Join-Path $root 'win-out'
New-Item -ItemType Directory -Force $out | Out-Null
$appdata = Join-Path $root 'win-appdata'
$data = Join-Path $appdata 'SyncMusic'
New-Item -ItemType Directory -Force $data | Out-Null
$music = Join-Path $root 'win-music'

# Test library with Cyrillic names
foreach ($a in 1..5) {
    $dir = Join-Path $music "Артист $a\Альбом"
    New-Item -ItemType Directory -Force $dir | Out-Null
    foreach ($t in 1..8) {
        [IO.File]::WriteAllBytes((Join-Path $dir "$t Пісня.mp3"), (New-Object byte[] (50000 + $t * 1000)))
    }
}
$settings = @{ musicFolder = $music; autostart = $false; firstRunDone = $true; closeToTray = $false; trayHintShown = $true } | ConvertTo-Json
[IO.File]::WriteAllText((Join-Path $data 'settings.json'), $settings)
$env:APPDATA = $appdata

$exe = Join-Path $root 'desktopApp\build\compose\binaries\main\app\SyncMusic\SyncMusic.exe'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

function Shot($name) {
    try {
        $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
        $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size)
        $bmp.Save((Join-Path $out "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
        $g.Dispose(); $bmp.Dispose()
    } catch { "screenshot $name failed: $_" | Out-File -Append (Join-Path $out 'win-notes.txt') }
}

function WaitLog($pattern, $seconds) {
    $log = Join-Path $data 'syncmusic.log'
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $seconds) {
        if ((Test-Path $log) -and (Select-String -Path $log -Pattern $pattern -SimpleMatch -Quiet)) { return $true }
        Start-Sleep -Milliseconds 250
    }
    return $false
}

function CountLog($pattern) {
    $log = Join-Path $data 'syncmusic.log'
    if (Test-Path $log) { return @(Select-String -Path $log -Pattern $pattern -SimpleMatch).Count } else { return 0 }
}

# 1. First launch (no CDS archive yet)
$sw0 = [Diagnostics.Stopwatch]::StartNew()
$p = Start-Process $exe -ArgumentList '--status' -PassThru
WaitLog 'Window composed' 60 | Out-Null
"First launch: window composed after $([int]$sw0.Elapsed.TotalMilliseconds) ms (wall clock)" | Out-File -Append (Join-Path $out 'win-notes.txt')
Start-Sleep 6
Shot 'win-1-first-launch'

# 2. Delete two files: the counter must follow without pressing anything
Remove-Item -LiteralPath (Join-Path $music 'Артист 1\Альбом\1 Пісня.mp3')
Remove-Item -LiteralPath (Join-Path $music 'Артист 1\Альбом\2 Пісня.mp3')
WaitLog 'Scanned 38 files' 20 | Out-Null
Start-Sleep 2
Shot 'win-2-after-delete'

# 3. "Close" to the tray, then launch again: the running copy must show its window
'hide' | Out-File -Encoding utf8NoBOM (Join-Path $data 'command.txt')
Start-Sleep 3
Shot 'win-3a-hidden'
$sw = [Diagnostics.Stopwatch]::StartNew()
Start-Process $exe -ArgumentList '--status' | Out-Null
$before = 0
while ($sw.Elapsed.TotalSeconds -lt 30) {
    if ((CountLog 'Showing window on request') -ge 1) { break }
    Start-Sleep -Milliseconds 50
}
"Reopen from the tray via a second launch: window shown after $([int]$sw.Elapsed.TotalMilliseconds) ms" | Out-File -Append (Join-Path $out 'win-notes.txt')
Start-Sleep 2
Shot 'win-3b-reopened'

# 4. Quit normally, then launch again
'quit' | Out-File -Encoding utf8NoBOM (Join-Path $data 'command.txt')
if (-not $p.WaitForExit(30000)) { "app did not quit" | Out-File -Append (Join-Path $out 'win-notes.txt'); Stop-Process -Id $p.Id -Force }

foreach ($i in 1..2) {
    $p = Start-Process $exe -ArgumentList '--status' -PassThru
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt 60 -and (CountLog 'Window composed') -lt (1 + $i)) { Start-Sleep -Milliseconds 50 }
    "Relaunch ${i}: window composed after $([int]$sw.Elapsed.TotalMilliseconds) ms (wall clock)" | Out-File -Append (Join-Path $out 'win-notes.txt')
    Start-Sleep 4
    Shot "win-4-relaunch-$i"
    'quit' | Out-File -Encoding utf8NoBOM (Join-Path $data 'command.txt')
    if (-not $p.WaitForExit(30000)) { Stop-Process -Id $p.Id -Force }
}

Copy-Item (Join-Path $data 'syncmusic.log') (Join-Path $out 'win-syncmusic.log') -ErrorAction SilentlyContinue
Get-ChildItem $out | Format-Table Name, Length | Out-String | Write-Host
Get-Content (Join-Path $out 'win-syncmusic.log') -ErrorAction SilentlyContinue | Write-Host
Get-Content (Join-Path $out 'win-notes.txt') -ErrorAction SilentlyContinue | Write-Host
exit 0

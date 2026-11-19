param(
    [string]$DropDir = "",
    [switch]$ClipboardToNote,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Files
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($DropDir)) {
    $DropDir = Join-Path $env:USERPROFILE "BgoomDrop"
}

$FilesDir = Join-Path $DropDir "outbox\files"
$EventsDir = Join-Path $DropDir "outbox\events"
$NotesDir = Join-Path $DropDir "notes"
New-Item -ItemType Directory -Force -Path $FilesDir, $EventsDir, $NotesDir | Out-Null

function Show-Notify($Title, $Body) {
    try {
        Add-Type -AssemblyName System.Windows.Forms
        $n = New-Object System.Windows.Forms.NotifyIcon
        $n.Icon = [System.Drawing.SystemIcons]::Information
        $n.Visible = $true
        $n.ShowBalloonTip(3500, $Title, $Body, [System.Windows.Forms.ToolTipIcon]::Info)
        Start-Sleep -Milliseconds 300
        $n.Dispose()
    } catch {}
}

$ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

if ($ClipboardToNote) {
    $text = Get-Clipboard -Raw
    if ([string]::IsNullOrWhiteSpace($text)) { exit 0 }
    $uid = [Guid]::NewGuid().ToString("N").Substring(0, 8)
    $firstLine = ($text.Trim() -split "`r?`n")[0]
    $title = if ($firstLine.Length -gt 28) { $firstLine.Substring(0, 28) } else { $firstLine }
    $ev = @{
        type = "note"
        id = $uid
        title = $title
        content = $text.Trim()
        createdAt = $ts
    } | ConvertTo-Json -Compress
    $evPath = Join-Path $EventsDir "${ts}_${uid}.json"
    [System.IO.File]::WriteAllText($evPath, $ev, [System.Text.UTF8Encoding]::new($false))
    Show-Notify "大爆 BGOOM · 便签已发往手机" $title
    exit 0
}

$sent = @()
foreach ($raw in $Files) {
    if (-not (Test-Path $raw)) { continue }
    $item = Get-Item $raw
    $uid = [Guid]::NewGuid().ToString("N").Substring(0, 8)
    $displayName = $item.Name
    $dstFile = Join-Path $FilesDir "${ts}_${uid}_${displayName}"
    if ($item.PSIsContainer) {
        $displayName = "$($item.Name).zip"
        $dstFile = Join-Path $FilesDir "${ts}_${uid}_${displayName}"
        Compress-Archive -Path $item.FullName -DestinationPath $dstFile -Force
    } else {
        Copy-Item -Path $item.FullName -Destination $dstFile -Force
    }
    $storedName = [System.IO.Path]::GetFileName($dstFile)
    $ev = @{
        type = "file"
        id = $uid
        fileName = $displayName
        storedName = $storedName
        createdAt = $ts
    } | ConvertTo-Json -Compress
    $evPath = Join-Path $EventsDir "${ts}_${uid}.json"
    [System.IO.File]::WriteAllText($evPath, $ev, [System.Text.UTF8Encoding]::new($false))
    $sent += $displayName
}

if ($sent.Count -gt 0) {
    $summary = if ($sent.Count -eq 1) { $sent[0] } else { "$($sent[0]) 等 $($sent.Count) 个文件" }
    Show-Notify "大爆 BGOOM · 已发往手机" $summary
}

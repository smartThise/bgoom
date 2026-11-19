param(
    [string]$DropDir = ""
)

$ErrorActionPreference = "Stop"
$ScriptSrc = Join-Path $PSScriptRoot "bgoom-send.ps1"
$InstallDir = Join-Path $env:LOCALAPPDATA "BGOOM"
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
$InstalledScript = Join-Path $InstallDir "bgoom-send.ps1"
Copy-Item -Path $ScriptSrc -Destination $InstalledScript -Force

$SendToDir = Join-Path $env:APPDATA "Microsoft\Windows\SendTo"
$Wsh = New-Object -ComObject WScript.Shell

# 1. Windows Explorer Right-Click -> Send To -> 发送到大爆手机
$LnkFile = Join-Path $SendToDir "发送到大爆手机.lnk"
$Sc = $Wsh.CreateShortcut($LnkFile)
$Sc.TargetPath = "powershell.exe"
$DropArg = if ($DropDir) { "-DropDir `"$DropDir`"" } else { "" }
$Sc.Arguments = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$InstalledScript`" $DropArg"
$Sc.Description = "通过大爆 BGOOM 发送文件到手机"
$Sc.Save()

# 2. Desktop Shortcut for sending clipboard/selected text to phone notes (can bind a Windows hotkey like Ctrl+Alt+B)
$ClipLnk = Join-Path ([Environment]::GetFolderPath("Desktop")) "发送剪贴板到大爆手机便签.lnk"
$Sc2 = $Wsh.CreateShortcut($ClipLnk)
$Sc2.TargetPath = "powershell.exe"
$Sc2.Arguments = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$InstalledScript`" $DropArg -ClipboardToNote"
$Sc2.Description = "把当前剪贴板文字发送到手机大爆便签"
$Sc2.Save()

Write-Host "✅ 安装完成（零 UI、零常驻进程）："
Write-Host "  1. 资源管理器右键文件 -> 发送到 -> 「发送到大爆手机」"
Write-Host "  2. 双击桌面快捷方式（或为其绑定 Ctrl+Alt+B）即可把剪贴板文字发往手机便签"
Write-Host "  提示：如 SSH 服务端运行在 WSL 中，可重新运行并传参 -DropDir '\\wsl$\Ubuntu\home\<用户名>\BgoomDrop'"

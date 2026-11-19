# 大爆 BGOOM 电脑端极简无 UI 适配脚本（macOS / Windows）

零常驻进程、零内存占用、零额外端口：完全复用你现有的加密 SSH 通道。

## macOS 安装（Finder 右键传文件 + 任意应用选中文字发手机便签）

```bash
chmod +x companion/mac/install-mac.sh companion/mac/bgoom-send
./companion/mac/install-mac.sh
```

安装后：
1. **Finder 右键任意文件/文件夹** → `快速操作` → **`发送到大爆手机`**
2. **Safari / Chrome / PDF / VS Code / 备忘录中选中任意文字** → 右键 → `服务` → **`发送到手机便签 (大爆)`**
3. 手机端在「大爆」主页或文件互传页开启 **「实时接收」**，1–2 秒内自动接收并弹出系统通知/状态栏实况胶囊，点击通知可直接打开文件。
4. 反向（手机 → 电脑）**无需安装任何常驻程序**，手机发送文件/便签后会自动在 Mac 弹出原生系统通知。

卸载：`./companion/mac/uninstall-mac.sh`

## Windows 安装（右键「发送到」传文件 + 剪贴板发便签）

在 PowerShell 中运行（无需管理员权限）：

```powershell
powershell -ExecutionPolicy Bypass -File .\companion\windows\install-windows.ps1
```

如果 SSH 服务运行在 WSL 中，指定 WSL 收件目录即可：

```powershell
powershell -ExecutionPolicy Bypass -File .\companion\windows\install-windows.ps1 -DropDir "\\wsl$\Ubuntu\home\<username>\BgoomDrop"
```

# 大爆（BGOOM）

极简 Gemini 风格的 Android 离线文档扫描 · 极速 OCR/Typst · 多尺寸证件照 · 多设备仅私钥加密 SSH 中转站（附 macOS / Windows 无 UI 右键适配端）。

## 核心特性（零 GMS 依赖 · 仅 `arm64-v8a`）

1. **多设备仅私钥加密 SSH 中转站（无需 Shizuku / ADB / Root）**
   - **严禁密码登录**：仅接受 SSH 私钥（Ed25519 / RSA / ECDSA + 可选私钥口令），支持应用内一键生成 Ed25519 密钥对，私钥经 Android Keystore (`AES256_GCM`) 加密存储，首次连接锁定主机指纹（TOFU）
   - **系统相册/下载/文件管理直接「分享到大爆」**：支持从相册、系统下载、文件管理、微信等一键直传电脑，或直接送入扫描 / OCR / 证件照
   - **落盘即用**：从电脑下载的文件直接存入系统公共目录 **`下载 / BGOOM`**，照片与扫描图存入 **`相册 / BGOOM`**，点击记录可直接调用系统应用打开
   - **双向系统通知 & Android 16 实况胶囊（Live Updates）**：
     - 手机发往电脑：零常驻守护进程，传输完成直接触发 macOS / Linux / Windows 原生系统通知
     - 电脑发往手机：开启「实时接收」后通过现有加密 SSH 通道 1–2 秒内自动收件，实时刷新状态栏实况胶囊进度并弹出高优先级来件通知（点击直接打开文件 / 自动复制便签文字）
   - **多设备中转站（Hub）**：设备之间互不直连，支持 A 设备 → 手机 → B 设备加密中转、本地 SQLite 秒存 + 复用长连接同步 Markdown 便签、以及跨设备剪贴板同步

2. **极简无 UI 电脑端右键适配（`companion/`）**
   - **macOS**：安装后无任何窗口、零后台内存，支持 **Finder 右键文件/文件夹 → 快速操作 → 「发送到大爆手机」**，以及 **任意应用选中文字 → 右键 → 服务 → 「发送到手机便签 (大爆)」**（详见 [companion/README.md](companion/README.md)）
   - **Windows**：无需管理员权限，安装到资源管理器右键 **「发送到 → 发送到大爆手机」**，支持普通 Windows 与 WSL 收件目录

3. **极简文档拍照扫描（纯 C++17 NDK，零 OpenCV）**
   - CameraX 拍摄 + 相册导入（自动 EXIF 纠偏）→ 自动四角检测与手调 → 单应矩阵透视矫正 → 5 档文档滤镜（去影增白、Sauvola 极清黑白、去影灰度、彩色锐化、原图矫正）→ 多页合成 PDF

4. **多尺寸精修证件照（纯离线）**
   - 内置 **HivisionIDPhotos MODNet FP16 ONNX**（12.4 MB）+ 自研 C++17 **导向滤波发丝精修 + 衣领空洞修补 + 边缘去溢色 + 智能人脸居中构图 + 自然打光**
   - 支持现场拍摄或相册选图、双指缩放拖动、一寸/小一寸/大一寸/二寸/小二寸/四六级/教资/美签（300 DPI）、6 种纯色/渐变底色与 **6 寸冲印排版照**

5. **混合 OCR + 确定性 AST 转译 + 双模式 Typst 一键出 PDF**
   - **离线文字 OCR**：内置 **PP-OCRv5 Mobile**（20.5 MB，18,383 字大词表，含中英文潦草手写强化）
   - **离线公式 OCR**：内置 **Texo 20M INT8**（19.5 MB，687 精简 LaTeX Token）
   - **远端高精度 OCR**：支持 OpenAI 兼容视觉 API 与 SSH 远端命令模板（对接 OXR / PaddleOCR-VL / MiniCPM / Gemini / LM Studio）
   - **亚毫秒 AST 转译与离线编译**：内置 Rust **Tylax 0.3.8**（`<1ms` 确定性 `LaTeX ↔ Typst` 双向互转）与 **Typst 0.14.2** 离线编译引擎（读取系统 CJK 字体出 PDF），同时支持一键切换 SSH 调电脑端 `typst compile`

6. **应用内 GitHub Releases 自动检测更新**
   - 启动静默检测 / 设置页手动检查 `GitHub Releases` 最新版本，支持应用内显示进度下载 APK 并直接调起 Android 系统安装器。

## 构建

```bash
# 1. 编译 Rust Typst + Tylax JNI 库（需 aarch64-linux-android target 与 Android NDK 27）
cd rust-typst
cargo build --release --target aarch64-linux-android
cp target/aarch64-linux-android/release/libbgoom_typst.so ../app/src/main/jniLibs/arm64-v8a/

# 2. 运行单元测试并打包 Release APK
cd ..
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

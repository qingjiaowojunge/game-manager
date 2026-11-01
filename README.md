# 游戏管理软件 · 真实数据版（v2）

用 **HTML / JS / CSS** 写应用，打包成 **Android APK** 装到手机上运行。
应用本体就是 `index.html`（内联了全部 CSS/JS），安卓端只有一个极薄的 WebView 壳把它装进 APK 在手机里跑。

**这版彻底去除了假数据**——所有数据都来自手机真实系统：
- 应用清单来自 `PackageManager`（手机真实已装应用，家长从中勾选要管控的）
- 使用时长来自 `UsageStatsManager`（每个应用真实前台耗时，不是应用自己数的）
- 超额判定与锁机由原生前台服务读取真实时长后执行（`DevicePolicyManager.lockNow()` 真锁屏）

## 工作流程（真实可用）
1. **家长端 → 应用页**：拉取手机真实已装应用列表，勾选要管控的应用并设置"每日分钟数"。
2. **家长端 → 概览 / 统计**：今日已用、剩余、限额环形图，以及每个应用近 7 天真实时长柱状图，全部来自系统真实用量。
3. **孩子端**：展示每个应用"真实剩余时间"并实时刷新；某应用真实用量达到限额即弹出锁屏遮罩。
4. **后台监控**：原生 `MonitorService` 每 15 秒读取真实用量，任一受管控应用今日真实时长达限额即真锁屏；无障碍服务在超限应用处于前台时将其退回桌面。
5. 家长可在统计页"加时 +15 分"给某个应用放宽当日额度（真实用量无法被应用篡改，只能由家长提额）。

默认家长密码 `1234`，可在「我的」页修改（进入家长端需输密码，防止孩子篡改）。

## 目录结构
```
游戏管理软件v1/
├── index.html                 # 应用本体（HTML/JS/CSS），真实数据驱动，你主要改这里
├── android/                   # 极薄 WebView 壳 + 原生桥接层
│   ├── app/src/main/java/.../
│   │   ├── MainActivity.java          # WebView 加载 assets/index.html
│   │   ├── GameBridge.kt              # @JavascriptInterface 暴露真实数据/控制
│   │   ├── UsageUtil.kt               # 已装应用 + 真实用量(UsageStats) 共享工具
│   │   ├── MonitorService.kt          # 前台服务：读真实用量，超额锁屏
│   │   ├── AppMonitorAccessibilityService.kt  # 超限应用退回桌面
│   │   └── AdminReceiver.kt           # 设备管理员
│   ├── app/src/main/res/...   # 图标、主题、无障碍描述
│   ├── app/build.gradle / build.gradle / settings.gradle / gradle.properties
├── .github/workflows/build-apk.yml   # 推到 GitHub 自动出 APK
├── push.bat                   # 一键推送并触发出包（Windows）
└── README.md
```

## 本地预览（不打包也能看）
直接双击 `index.html`，或起个静态服务器：
```bash
python -m http.server 8000
# 浏览器打开 http://localhost:8000
```
> 浏览器里是"网页预览模式"：没有真实设备数据，界面只展示结构。装到手机（APK）后才读得到真实已装应用与真实用量。

## 一键出 APK（推到 GitHub，GitHub 自动打包）
本仓库内置 `.github/workflows/build-apk.yml`：推送即自动把 `index.html` 编译进 APK 并发 Release。
最简单的做法：在本目录运行 `push.bat`（需先 `gh auth login`）。或手动：
```bash
git init
git add .
git commit -m "first version"
git branch -M main
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
```
等 Actions 跑完（约 3–5 分钟），进仓库 **Releases** 下载 `GameManager.apk`；或扫 Release 里的 `qr.png` 直接装。
之后每次 `git push` 都会重新出包，下载链接固定为 `releases/latest/download/GameManager.apk`。

## 必须授权（否则读不到真实数据）
| 权限 | 作用 | 是否必需 |
|------|------|----------|
| **使用情况访问**（Usage Access） | 读取每个应用真实前台时长 | **必需**——不授权则所有时长显示为 0，监控失效 |
| **设备管理员** | 真锁屏幕 `lockNow()` | 需要真锁屏时必需 |
| **无障碍服务** | 超限应用退回桌面（拦截） | 可选增强 |
| （安装时自动声明）`QUERY_ALL_PACKAGES` | 列出手机全部已装应用 | 系统级，Android 11+ 必需 |

授权入口在家长端「我的 → 系统能力授权」卡片，点对应按钮跳转系统设置，各授权一次即可。

## 未授权 / 已授权 能力对照
| 能力 | 未授权 | 已授权 |
|------|--------|--------|
| 列出真实已装应用 | 不可用（需 QUERY_ALL_PACKAGES，安装即带） | 可用 |
| 读取真实使用时长 | **不可用（0）** | 可用 |
| 概览 / 统计（真实数据） | 不可用 | 可用 |
| 孩子端真实剩余时间 | 不可用 | 可用 |
| 真锁屏幕（lockNow） | 不可用 | 可用 |
| 到时自动锁屏 / 退回桌面 | 不可用 | 可用（设备管理员 + 无障碍） |

## 已知边界（诚实说明）
- 应用无法篡改系统记录的真实用量；家长只能"加时"放宽限额，不能把已用时长清零。
- 真锁屏依赖设备管理员；未授权时仅显示应用内锁屏遮罩，孩子仍可手动退出遮罩（弱管控）。
- 不同厂商 ROM 对设备管理员 / 无障碍 / 后台保活有额外限制，必要时需在系统"电池/自启动/应用管理"里加白。
- 本应用面向家长自主管理自用或子女设备，请遵守当地法律法规与未成年人保护相关规定。

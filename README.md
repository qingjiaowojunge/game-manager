# 游戏管理软件 · 第一版（真实可用版）

用 **HTML / JS / CSS** 写的应用，打包成 **Android APK** 装到手机上运行。
应用本体就是 `index.html`（内联了全部 CSS/JS），安卓端只有一个极薄的 WebView 壳把它装进 APK 在手机里跑。

## 这版是真的能用，不是假数据
- **真实计时**：孩子端选游戏 → 输密码 → 开始倒计时，每秒真实扣减，到时自动锁机。
- **真实记录**：每一局玩了哪个游戏、起了多久都写进本地（localStorage），统计页的近 7 天柱状图、今日排行都是从真实记录算出来的，不再是写死的示例数字。
- **真实管控逻辑**：每日总时长上限、单游戏限额、临时加时、一键锁机、重置今日——都是真实生效的。
- 默认家长密码 `1234`，每日上限 `120` 分钟，可在「我的」页改。

## 目录结构
```
游戏管理软件v1/
├── index.html                 # 应用本体（HTML/JS/CSS），你主要改这里
├── android/                   # 极薄 WebView 壳，仅把 index.html 包进 APK
│   ├── app/src/main/java/.../MainActivity.java
│   ├── app/src/main/res/...   # 图标、主题
│   ├── app/build.gradle
│   ├── build.gradle
│   └── settings.gradle
├── .github/workflows/build-apk.yml   # 推到 GitHub 自动出 APK
└── README.md
```

## 本地预览（不打包也能看）
直接双击 `index.html`，或起个静态服务器：
```bash
python -m http.server 8000
# 浏览器打开 http://localhost:8000
```

## 一键出 APK（推到 GitHub，GitHub 自动打包）
本仓库内置 `.github/workflows/build-apk.yml`：推送即自动把 `index.html` 编译进 APK 并发 Release。

1. 在 GitHub 新建仓库（例如 `game-manager`）。
2. 把本目录推送到 `main`：
   ```bash
   git init
   git add .
   git commit -m "first version"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/<仓库名>.git
   git push -u origin main
   ```
3. 等 Actions 跑完，进入仓库 **Releases** 页，下载 `GameManager.apk`。
4. 手机下载 APK → 开启「设置 → 安全 → 允许未知来源」→ 安装。
   Release 里还带了 `qr.png` 二维码，手机扫一下直接下载。

之后每次 `git push` 都会重新出包，下载链接不变（固定为 `releases/latest/download/GameManager.apk`）。

## 系统级能力（原生桥接，已实现）
`android/` 壳里已加入 Kotlin 原生层，通过 `GameBridge`（`@JavascriptInterface`）把安卓系统能力暴露给网页：
- **真实锁屏**：家长端「一键锁机」、孩子端到时锁机，调用 `DevicePolicyManager.lockNow()` 真锁屏幕（需启用设备管理员）。
- **到点自动锁**：孩子开始游戏时网页把额度截止时间交给 `MonitorService`（前台服务），到点由原生服务真实锁屏。
- **拦截别的应用**：`AppMonitorAccessibilityService` 在额度超时且前台是受管控游戏时，将其退回桌面并锁屏。

要在手机上让上述能力**真正生效**，需手动授权（设置一次即可）：
1. **设备管理员**：家长端「系统能力」卡片 → 「启用设备管理员」→ 在系统页激活（用于锁屏）。
2. **用量统计权限**：「授予用量统计」→ 在设置里允许（用于查询前台应用）。
3. **无障碍服务（可选，拦截别的应用）**：设置 → 无障碍 → 找到「游戏管理软件」→ 开启。

未授权时，应用仍按**应用内计时 / 锁机遮罩**方式正常工作；授权后升级为**系统级强管控**。

未授权与授权的能力对照：
| 能力 | 未授权 | 已授权 |
|------|--------|--------|
| 计时 / 记录 / 统计 | 真实可用 | 真实可用 |
| 应用内锁机遮罩 | 可用 | 可用 |
| 真锁屏幕（lockNow） | 不可用 | 可用 |
| 到时自动锁屏 | 不可用（仅遮罩） | 可用 |
| 拦截/退回其他游戏 | 不可用 | 可用（开无障碍） |


# 游戏管理软件 · 真实数据版（v2）

用 **HTML / JS / CSS** 写应用，打包成 **Android APK** 装到手机上运行。
应用本体就是 `index.html`（内联了全部 CSS/JS），安卓端只有一个极薄的 WebView 壳把它装进 APK 在手机里跑。

## v2 大改：修掉"一碰就锁、界面自动关闭"

真机实测暴露出三个连锁问题，全部指向同一类错误——**判定与执行没有边界**：

| 现象 | 真实根因 | v2 修法 |
|------|----------|---------|
| 界面一弹出来就自动关闭 | 孩子端的全屏遮罩只要**任一**应用超限就盖满屏幕，而且**没有任何按钮**，盖住就退不出去 | 遮罩不再自动弹出，并补了「返回」出口；超限改由原生拦截层处理 |
| 一碰就锁定、接着退出界面 | `MonitorService` 每 15 秒读一次用量后**无条件** `lockNow()`，完全不看当前前台是哪个应用——所以你在这个 App 里点任何东西，15 秒内屏幕就被锁 | 改为**前台感知**：只在受管控应用真的在前台时才动作；本应用/桌面/系统界面永不触发 |
| 超限后一整天都恢复不了 | 真实用量取自系统、**归零不了**，超一次当天永久超限，没有任何豁免通道 | 新增**今日加时 / 今日豁免**（用加时抵消已用时长，不改每日基准限额） |

### 新的执行模型（默认最温和）

```
前台应用 → Guard 单一裁定（暂停？自己/桌面/系统？超限？冷却中？）
        → Enforcer 统一执行
             ① 全屏拦截层（悬浮窗，默认）   ← 需要「显示在其他应用上层」
             ② 退回桌面（可选）            ← 需要无障碍
             ③ 锁屏（可选，默认关）        ← 需要设备管理员
```

关键约束：

- **默认只做①**，不锁屏、不退回桌面。也就是说默认行为绝不会"锁你的手机"。
- 同一应用 **90 秒冷却**，不会连环弹。
- 家长打开本应用时，拦截层**自动撤掉**，绝不挡住家长自己。
- 家长可以随时**暂停管控 30 分钟 / 立即恢复**，暂停期间完全不拦截。

## v2.1 新增功能（本轮）

在 v2 修净"乱锁乱弹"的基础上，把管控能力做细：

| 功能 | 说明 | 原生位置 |
|------|------|----------|
| **工作日 / 周末分开限额** | 同一个应用可以设两套额度（如上学日 30 分、周末 90 分），按当天是周六日自动切换 | `Guard.Rule(weekday, weekend)` |
| **每日总时长上限** | 所有受管应用**合计**不得超 N 分钟，防止"每个都不超、加起来玩半天" | `Guard.totalLimitMin` |
| **免打扰时段（宵禁）** | 如 22:00→07:00，时段内所有受管应用一律拦；**支持跨零点** | `Guard.inCurfew()` |
| **快用完提醒** | 剩 N 分钟时推一条高优先级通知，每个应用每天只提醒一次（跨天自动重置） | `Guard.shouldNotifyRemaining` + `game_warn` 渠道 |
| **今日使用排行 Top5** | 概览页直接看"今天都在用什么"，不受管的应用也统计 | `UsageUtil.rankingJson` |
| **操作记录（家长审计）** | 加时 / 豁免 / 拦截 / 暂停 / 恢复都留痕，保留最近 40 条，可一键清空 | `Guard.audit` |
| **崩溃日志页** | 「我的 → 崩溃日志」可直接看到上次崩溃的时间、线程、版本、完整栈 | `App.kt` + `GameBridge.getLastCrash` |
| **批量设限额** | 应用页勾选多个应用，一次写入同一个额度 | `index.html #batchApply` |
| **管控状态条** | 顶部常驻：绿=管控中 / 橙=已暂停或宵禁中 / 灰=未配置，一眼看清现在到底管不管 | `Guard.stateJson` |

同时把**所有** `@JavascriptInterface` 方法都套上异常护栏（`safe` / `safeUnit`）——桥接方法抛出的异常会打到 WebView 的 JavaBridge 线程并**直接杀掉应用**，这是"点一下就闪退"的典型死法。

## v2.2：堵掉最后一种"一打开就闪退"

`MainActivity.onRenderProcessGone()`。

WebView 的渲染进程是独立进程。内存紧张、或厂商 ROM 清理后台时，系统会**直接杀掉渲染进程，并默认连宿主应用一起杀掉**。这种"闪退"不是 Java 异常，`CrashHandler` 抓不到，日志里什么都没有——是最难查的一种。

现在改成：拆掉坏掉的 WebView，在主线程消息队列里**重建一个新 WebView 并重新加载页面**，返回 `true` 告诉系统"我自己处理"，应用继续活着。首次启动和重建走的是同一条 `attachWeb()` 路径，不会出现两套逻辑。

## v2.4 新增：专注模式 + 通知栏实时剩余

| 功能 | 说明 |
|---|---|
| **专注模式** | 概览页选 25/45/60/90 分钟，一键开始。期间**受管应用一律打不开**，且**不消耗、不占用今天的额度**——专注结束该用多少还是多少。只作用于你勾选过的应用，电话、短信、相机不会被误锁。拦截页在专注期文案会变成「XX 专注模式中」，第一个按钮变成「家长：结束专注（需密码）」，不会伪装成"时间用完了"。 |
| **通知栏实时剩余** | 常驻通知从「正在监控 N 个应用」改成显示**最紧张的那个**：`王者荣耀 还剩 12 分钟` / `今日总时长还剩 40 分钟` / `专注模式中，还剩 23 分钟`。同时修掉一个隐患：这条通知原本只有服务启动时生成一次，之后永不更新，显示的数字会一直是旧的。 |
| **密码防猜** | 连续错 5 次锁 1 分钟；保存密码时至少 4 位，输入 `1234` 会额外提醒一次。 |

## 上一版（v1）的下载地址

- 手机下载页：<https://qingjiaowojunge.github.io/game-manager/>
- 仓库：<https://github.com/qingjiaowojunge/game-manager>

> 当前版本 **v2.4 (8)**（`versionName 2.4` / `versionCode 8`），推到这个地址即最新。
> 下载文件名带版本号（如 `GameManager-v2.4-8.apk`），**不会被浏览器缓存成旧包**；换版本时文件名必然变化。

### 本版产物核验（不是"构建成功"就算数，已逐个查过）

| 检查项 | 结果 |
|---|---|
| CI | run `41632958894`(v1.0.8) / `41633737189`(v1.0.9) / `41634691926`(v1.0.10) 均 build ✅ pages ✅ |
| Release | **`v1.0.14`**（v2.4 build 8）· `GameManager-v2.4-8.apk` · 2,102,355 字节 |
| SHA256 | `485966900b69b2a1…`（完整值见下载页或 `apk.sha256`） |
| Pages 上的包与 Release 是否同一份 | **是**（同一 sha256） |
| `assets/index.html` | 含 `getTodayRanking / getLastCrash / getAuditLog / addBonus / exemptToday / batchApply / guardBar`，含「工作日 / 周末 / 免打扰 / 每日总时长 / 操作记录 / 崩溃日志」 |
| dex | 含 `Guard / Enforcer / OverlayBlocker / BlockActivity / MonitorService / UsageUtil / GameBridge / MainActivity`，含 `isBlockedNow / shouldNotifyRemaining / inCurfew / totalUsedMinutes / rankingJson / readCrash / auditJson / requestHome` |
| AndroidManifest | 含 `SYSTEM_ALERT_WINDOW`、`PACKAGE_USAGE_STATS`、`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`、`QUERY_ALL_PACKAGES`、`BlockActivity`、`App`；`versionName=2.1` |


**这版彻底去除了假数据**——所有数据都来自手机真实系统：
- 应用清单来自 `PackageManager`（手机真实已装应用，家长从中勾选要管控的）
- 使用时长来自 `UsageStatsManager`（每个应用真实前台耗时，不是应用自己数的）
- 超额判定与锁机由原生前台服务读取真实时长后执行（`DevicePolicyManager.lockNow()` 真锁屏）

## 工作流程（真实可用）
1. **家长端 → 应用页**：拉取手机真实已装应用列表，勾选要管控的应用，设置**工作日 / 周末**两套每日分钟数（也可勾多个批量设）。
2. **家长端 → 概览 / 统计**：今日已用、剩余、限额环形图，Top5 使用排行，以及每个应用近 7 天真实时长柱状图，全部来自系统真实用量。
3. **孩子端**：展示每个应用"真实剩余时间"并实时刷新；剩余不足时推通知，用完由原生拦截层盖住。
4. **后台监控**：原生 `MonitorService` 每 10 秒做一次**前台感知**检查，只对"真的在前台且确实超限"的受管应用动手；同一应用 90 秒冷却，绝不连环弹。
5. **家长随时可干预**：加时 +15 / +30 分、今日豁免、暂停 30 分钟、切换执行策略。
6. **每一步都留痕**：加时、豁免、拦截、暂停、恢复全部写进「操作记录」。

默认家长密码 `1234`，可在「我的」页修改（进入家长端需输密码，防止孩子篡改）。

## 目录结构
```
游戏管理软件v1/
├── index.html                 # 应用本体（HTML/JS/CSS），真实数据驱动，你主要改这里
├── android/                   # 极薄 WebView 壳 + 原生桥接层
│   ├── app/src/main/java/.../
│   │   ├── MainActivity.kt            # WebView 加载 assets/index.html（唯一入口）
│   │   ├── GameBridge.kt              # @JavascriptInterface 暴露真实数据/控制
│   │   ├── UsageUtil.kt               # 已装应用 + 真实用量 + 真实前台应用
│   │   ├── Guard.kt                   # 判定中枢：暂停/前台感知/超限/冷却，唯一裁定入口
│   │   ├── Enforcer.kt                # 执行器：拦截层 →（可选）退桌面 →（可选）锁屏
│   │   ├── OverlayBlocker.kt          # 悬浮窗全屏拦截层（默认通道）
│   │   ├── BlockActivity.kt           # 全屏拦截页（悬浮窗不可用时的备用通道）
│   │   ├── MonitorService.kt          # 前台服务：每 10 秒前台感知检查
│   │   ├── AppMonitorAccessibilityService.kt  # 可选：退回桌面
│   │   └── AdminReceiver.kt           # 可选：设备管理员
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
等 Actions 跑完（约 3–5 分钟）。出包后有两种装法：

- **手机直接装（推荐，无需 GitHub 账号）**：打开 `https://<你的用户名>.github.io/<仓库名>/`（构建时自动部署到 GitHub Pages），或扫 Release 里的 `qr.png`，页面直接下载 `GameManager.apk`。
  - 需在仓库 **Settings → Pages → Source** 选择 **GitHub Actions**（`push.bat` 会用 `gh` 自动帮你开；没开成则手动设一次）。
- **Release 下载（需登录 GitHub）**：进仓库 **Releases** 点 `GameManager.apk`。

之后每次 `git push` 都会重新出包并更新 Pages，手机下载页地址不变。

## 必须授权（否则管控不生效）
| 权限 | 作用 | 是否必需 |
|------|------|----------|
| **使用情况访问**（Usage Access） | 读取每个应用真实前台时长 | **必需**——不授权则所有时长显示为 0，监控完全失效 |
| **显示在其他应用上层**（悬浮窗） | 超额时盖出全屏拦截层 | **必需**（Android 10+ 后台服务不能随意起 Activity，没有它弹不出拦截） |
| **设备管理员** | 真锁屏 `lockNow()` | 仅当你在「超额后怎么处理」里选了“拦截页 + 直接锁屏”才需要 |
| **无障碍服务** | 把超限应用退回桌面 | 仅当你在「超额后怎么处理」里选了“拦截页 + 退回桌面”才需要 |
| （安装时自动声明）`QUERY_ALL_PACKAGES` | 列出手机全部已装应用 | 系统级，Android 11+ 必需 |

授权入口在家长端「我的 → 系统能力授权」卡片，点对应按钮跳转系统设置。

## 未授权 / 已授权 能力对照
| 能力 | 未授权 | 已授权 |
|------|--------|--------|
| 列出真实已装应用 | 不可用（需 QUERY_ALL_PACKAGES，安装即带） | 可用 |
| 读取真实使用时长 | **不可用（0）** | 可用 |
| 概览 / 统计（真实数据） | 不可用 | 可用 |
| 孩子端真实剩余时间 | 不可用 | 可用 |
| 超额弹全屏拦截层 | 不可用（只能发一条通知） | 可用（悬浮窗） |
| 超额退回桌面 | 不可用 | 可用（无障碍） |
| 超额锁屏 | 不可用 | 可用（设备管理员） |

## 已知边界（诚实说明）
- 应用无法篡改系统记录的真实用量；家长只能"今日加时 / 今日豁免"来放宽，不能把已用时长清零。
- 默认策略**不锁屏**，只盖拦截层。孩子能按"回到桌面"关掉拦截层，但如果再打开那个应用，冷却结束后仍会被重新拦住。
- 拦截层是悬浮窗，**会盖住整台手机**（这正是拦截的意思）；家长打开本应用时会自动撤掉，不会挡自己。
- 若既不授权悬浮窗、也不开无障碍，超额时只能发通知——这是安卓系统限制，不是应用偷懒。
- 不同厂商 ROM 对悬浮窗 / 设备管理员 / 无障碍 / 后台保活有额外限制，必要时需在系统"电池/自启动/应用管理"里加白。
- 本应用面向家长自主管理自用或子女设备，请遵守当地法律法规与未成年人保护相关规定。

## 出包踩坑记录（改代码前先看，避免重犯）
| 曾经报错 | 真实原因 | 正确做法 |
|---|---|---|
| 手机下载到的总是旧包 | 浏览器与 Pages CDN 按 **URL** 缓存，文件名固定就会一直下发旧包 | 产物文件名带版本号 `GameManager-v<版本>-<构建号>.apk`，每次发版文件名必变；下载页也标出版本号便于核对 |
| 下载页版本号显示为空 | `GITHUB_ENV` 只在**单个 job 内**有效，`pages` 是独立 job 拿不到 `build` 里设的变量 | 在 `pages` 任务里从产物文件名 `GameManager-v*-*.apk` 反解版本号 |
| `git push` 报 `schannel: failed to receive handshake` / `CONNECT tunnel failed, response 502` | 本机到 `github.com:443` 被出口代理拦截，但 `api.github.com` 正常 | 用 `python push_api.py`，走 Git Data API（blob→tree→commit→改 ref），内容按 tree 校验，等价 push 且照样触发 Actions |
| `sdkmanager tools` 退出码 1 | `android-actions/setup-android@v3` 与 runner 自带 cmdline-tools 冲突 | 用 runner 自带 `/usr/local/lib/android/sdk` + 显式 `sdkmanager` 装 platform/build-tools，并写 `android/local.properties` |
| `Unresolved reference: FEEDBACK_ALL_MASK` | 常量属于 `AccessibilityServiceInfo`，不是 `AccessibilityManager` | `AccessibilityServiceInfo.FEEDBACK_ALL_MASK` |
| `'onEnabled' overrides nothing` | Java 平台类型参数不能用 `Context?` / `Intent?` 重写 | 用非空 `Context` / `Intent`，或直接不写这两个重写 |
| `Type ...MainActivity is defined multiple times`（dex 阶段） | 同名 `.java` 与 `.kt` 同时存在 | 一个类只保留一份源码 |

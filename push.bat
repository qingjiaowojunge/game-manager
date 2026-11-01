@echo off
chcp 65001 >nul
REM ============================================================
REM  游戏管理软件 —— 一键推送到 GitHub，云端自动编译 APK
REM ============================================================
REM  使用前提（只需做一次）：
REM    1) 安装 GitHub CLI: https://cli.github.com/
REM    2) 打开终端执行:  gh auth login   （GitHub.com / HTTPS / 浏览器登录）
REM    3) 双击本文件，或在该目录下执行:  push.bat
REM ------------------------------------------------------------
REM  想改仓库名？改下面这行即可（默认公开仓库）
set "REPO=game-manager"
set "OWNER=qingjiaowojunge"

git branch -M main

echo.
echo [1/4] 创建 GitHub 仓库并首次推送...
gh repo create %REPO% --public ^
  --description "游戏管理软件：HTML/JS/CSS 应用 + WebView 壳 + 原生桥接，GitHub Actions 自动出 APK" ^
  --source=. --remote=origin --push
if errorlevel 1 (
  echo 仓库已存在或创建失败，改为直接推送 main 分支...
  git remote add origin https://github.com/%OWNER%/%REPO%.git 2>nul
  git push -u origin main
)

echo.
echo [2/4] 开启 GitHub Pages（让手机扫码免登录直接下载 APK）...
gh api -X POST "repos/%OWNER%/%REPO%/pages" -f build_type=workflow >nul 2>nul
if errorlevel 1 gh api -X PUT "repos/%OWNER%/%REPO%/pages" -f build_type=workflow >nul 2>nul
echo   （若上面失败：手动到仓库 Settings - Pages - Source 选 GitHub Actions）

echo.
echo [3/4] 触发构建...
gh workflow run "build-apk.yml" --repo "%OWNER%/%REPO%" >nul 2>nul

echo.
echo [4/4] 完成！接下来：
echo   - 构建进度: https://github.com/%OWNER%/%REPO%/actions
echo   - 手机下载页(免登录): https://%OWNER%.github.io/%REPO%/
echo   - Release 下载: https://github.com/%OWNER%/%REPO%/releases
echo.
echo   构建约 3-5 分钟；完成后手机打开上面"下载页"即可装 APK。
echo.
pause

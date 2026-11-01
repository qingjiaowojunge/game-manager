@echo off
REM ============================================================
REM  游戏管理软件 —— 一键推送到 GitHub 并触发自动出 APK
REM ============================================================
REM  使用前提（只需做一次）：
REM    1) 安装 GitHub CLI: https://cli.github.com/
REM    2) 打开终端执行:  gh auth login   （选 GitHub.com / HTTPS / 用浏览器登录）
REM    3) 双击本文件，或在该目录下执行:  push.bat
REM ------------------------------------------------------------
REM  想改仓库名？改下面这行即可（默认公开仓库，方便扫码下载 APK）
set "REPO=game-manager"
set "OWNER=qingjiaowojunge"

git branch -M main

echo.
echo [1/2] 创建 GitHub 仓库并首次推送（如已存在则走推送分支）...
gh repo create %REPO% --public ^
  --description "游戏管理软件：HTML/JS/CSS 应用 + WebView 壳 + 原生桥接，GitHub Actions 自动出 APK" ^
  --source=. --remote=origin --push

if errorlevel 1 (
  echo 仓库已存在或创建失败，尝试直接推送当前 main 分支...
  git remote add origin https://github.com/%OWNER%/%REPO%.git 2>nul
  git push -u origin main
)

echo.
echo [2/2] 推送完成！
echo   - 打开 https://github.com/%OWNER%/%REPO%/actions  查看自动构建进度（约 3-5 分钟）
echo   - 构建成功后打开 https://github.com/%OWNER%/%REPO%/releases 下载 GameManager.apk
echo   - 用手机扫 Releases 里的 qr.png 可直接下载安装
echo.
pause

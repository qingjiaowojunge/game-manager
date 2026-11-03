package com.example.gamemanager

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获。
 *
 * 为什么需要：目前只能靠"闪退"这个现象猜原因，太浪费轮次。
 * 这里把最后一次未捕获异常写进 files/crash_last.txt，
 * 下次打开应用在「我的 → 崩溃日志」里就能直接看到堆栈。
 *
 * 注意：只记录、不吞掉——写完仍然交回系统默认处理器，行为与原来一致。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val body = buildString {
                    append("时间: ").append(ts).append('\n')
                    append("线程: ").append(thread?.name ?: "?").append('\n')
                    append("版本: ").append(appVersionName()).append('\n')
                    append('\n')
                    append(sw.toString())
                }
                File(filesDir, CRASH_FILE).writeText(body)
            } catch (ignored: Throwable) {
                // 记录失败也绝不能影响主流程
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    companion object {
        const val CRASH_FILE = "crash_last.txt"

        fun readCrash(app: android.content.Context): String = try {
            val f = File(app.filesDir, CRASH_FILE)
            if (f.exists()) f.readText() else ""
        } catch (e: Exception) {
            ""
        }

        fun clearCrash(app: android.content.Context) {
            try {
                File(app.filesDir, CRASH_FILE).delete()
            } catch (e: Exception) { }
        }
    }
}

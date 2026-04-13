package com.appclone.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.appclone.ui.ErrorReportActivity
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global exception handler that catches all uncaught exceptions
 * and redirects to ErrorReportActivity instead of showing system crash dialog.
 */
class GlobalExceptionHandler(
    private val application: Context
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val errorBuilder = StringBuilder()
        errorBuilder.append("=== CRASH LOG ===\n")
        errorBuilder.append("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
        errorBuilder.append("Thread: ${thread.name}\n")
        errorBuilder.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        errorBuilder.append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        errorBuilder.append("App: AppClone v${getAppVersion(application)}\n\n")

        // Full stack trace
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        errorBuilder.append(sw.toString())

        // Also check for caused-by chain
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < 10) {
            errorBuilder.append("\n--- Caused by (level $depth) ---\n")
            val csw = StringWriter()
            cause.printStackTrace(PrintWriter(csw))
            errorBuilder.append(csw.toString())
            cause = cause.cause
            depth++
        }

        val errorMessage = errorBuilder.toString()

        // Launch error activity on main thread
        Handler(Looper.getMainLooper()).post {
            val intent = Intent(application, ErrorReportActivity::class.java).apply {
                putExtra(ErrorReportActivity.EXTRA_ERROR, errorMessage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            application.startActivity(intent)

            // Delay to let the error activity start, then kill the process
            Handler(Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(1)
            }, 500)
        }
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }
}

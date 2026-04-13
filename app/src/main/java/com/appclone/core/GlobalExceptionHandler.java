package com.appclone.core

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.appclone.ui.ErrorReportActivity
import java.io.PrintWriter
import java.io.StringWriter

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
        errorBuilder.append("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)" +
            ".format(java.util.Date())}\n")
        errorBuilder.append("Thread: ${thread.name}\n")
        errorBuilder.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        errorBuilder.append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        errorBuilder.append("App: AppClone v${getAppVersion(application)}\n")
        errorBuilder.append("\n")

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
            ErrorReportActivity.launch(application, errorMessage)
        }

        // Don't call default handler to prevent system crash dialog
        // The ErrorReportActivity will handle app termination when user closes it
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

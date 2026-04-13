package com.appclone.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.appclone.ui.ErrorReportActivity
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global exception handler that catches all uncaught exceptions
 * and redirects to ErrorReportActivity instead of showing system crash dialog.
 * Includes crash loop prevention to avoid infinite crash-restart cycles.
 */
class GlobalExceptionHandler(
    private val application: Context
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "GlobalExceptionHandler"
    }

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // Prevent crash loops - don't launch ErrorReportActivity if already shown
        if (ErrorReportActivity.hasShownError) {
            Log.e(TAG, "Crash loop detected, passing to default handler")
            defaultHandler?.uncaughtException(thread, throwable)
            return
        }

        val errorMessage = buildErrorReport(thread, throwable)

        Log.e(TAG, "Uncaught exception: ${throwable.message}", throwable)

        // Launch error activity on main thread - don't kill process
        // Let ErrorReportActivity handle the process lifecycle
        Handler(Looper.getMainLooper()).post {
            try {
                ErrorReportActivity.launch(application, errorMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch ErrorReportActivity", e)
                // Fallback: pass to default handler
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun buildErrorReport(thread: Thread, throwable: Throwable): String {
        val sb = StringBuilder()
        sb.append("=== CRASH LOG ===\n")
        sb.append("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
        sb.append("Thread: ${thread.name}\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("App: AppClone v${getAppVersion(application)}\n\n")

        // Full stack trace
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        sb.append(sw.toString())

        // Caused-by chain
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < 10) {
            sb.append("\n--- Caused by (level $depth) ---\n")
            val csw = StringWriter()
            cause.printStackTrace(PrintWriter(csw))
            sb.append(csw.toString())
            cause = cause.cause
            depth++
        }

        return sb.toString()
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

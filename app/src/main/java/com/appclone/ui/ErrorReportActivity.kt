package com.appclone.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.appclone.R

/**
 * Activity that displays error/crash details with a copy button.
 * All clone errors and uncaught exceptions are redirected here.
 * Uses simple TextView-based UI (no Material components) to avoid crashes.
 */
class ErrorReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ERROR = "error_text"

        /** Flag to prevent crash loops - only launch this once per process */
        @Volatile
        var hasShownError = false

        fun launch(context: Context, error: String) {
            if (hasShownError) return
            hasShownError = true

            try {
                val truncated = if (error.length > 15000) {
                    error.substring(0, 15000) + "\n\n... (đã cắt bớt, tổng ${error.length} ký tự)"
                } else {
                    error
                }

                val intent = Intent(context, ErrorReportActivity::class.java).apply {
                    putExtra(EXTRA_ERROR, truncated)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                // Last resort fallback: log and give up
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_error_report)

            val errorText = intent.getStringExtra(EXTRA_ERROR) ?: "Không có thông tin lỗi"

            // Set error text using simple findViewById
            val errorView = findViewById<TextView>(R.id.errorInput)
            errorView.text = errorText

            // Copy button
            findViewById<View>(R.id.btnCopy).setOnClickListener {
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Error Log", errorText))
                    Toast.makeText(this, "Đã sao chép!", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }

            // Close button (header)
            findViewById<View>(R.id.btnClose).setOnClickListener {
                closeApp()
            }

            // Close button (bottom)
            findViewById<View>(R.id.btnClose2).setOnClickListener {
                closeApp()
            }
        } catch (t: Throwable) {
            // If even this activity crashes, just exit immediately
            android.util.Log.e("ErrorReport", "ErrorReportActivity itself crashed", t)
            finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun closeApp() {
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onBackPressed() {
        closeApp()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure process dies when activity is destroyed
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

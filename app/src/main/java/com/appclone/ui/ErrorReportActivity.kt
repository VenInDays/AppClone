package com.appclone.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.appclone.R
import com.appclone.databinding.ActivityErrorReportBinding
import com.google.android.material.snackbar.Snackbar

/**
 * Activity that displays error/crash details with a copy button.
 * All clone errors and uncaught exceptions are redirected here.
 */
class ErrorReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityErrorReportBinding

    companion object {
        const val EXTRA_ERROR = "error_text"

        fun launch(context: Context, error: String) {
            // Truncate very large errors to avoid memory issues
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
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityErrorReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val errorText = intent.getStringExtra(EXTRA_ERROR) ?: "Không có thông tin lỗi"

        // Set error text
        binding.errorInput.setText(errorText)

        // Scroll to top
        binding.errorInput.post {
            binding.errorInput.setSelection(0)
        }

        setupToolbar()
        setupButtons()
    }

    private fun setupToolbar() {
        binding.topAppBar.setNavigationOnClickListener {
            closeApp()
        }
    }

    private fun setupButtons() {
        binding.btnCopy.setOnClickListener { view ->
            val text = binding.errorInput.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Error Log", text))
            Snackbar.make(view, "Đã sao chép lỗi!", Snackbar.LENGTH_SHORT).show()
        }

        binding.btnClose.setOnClickListener {
            closeApp()
        }
    }

    private fun closeApp() {
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
        System.exit(0)
    }

    override fun onBackPressed() {
        closeApp()
    }
}

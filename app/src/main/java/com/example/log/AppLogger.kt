package com.example.log

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object AppLogger {
    private const val MAX_LOG_COUNT = 3000
    private val logQueue = ConcurrentLinkedQueue<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        appendLog("DEBUG", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        appendLog("INFO", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        appendLog("WARN", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
            appendLog("ERROR", tag, "$message\n${Log.getStackTraceString(throwable)}")
        } else {
            Log.e(tag, message)
            appendLog("ERROR", tag, message)
        }
    }

    private fun appendLog(level: String, tag: String, message: String) {
        val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }
        val entry = "[$timestamp] [$level/$tag] $message"
        logQueue.add(entry)
        while (logQueue.size > MAX_LOG_COUNT) {
            logQueue.poll()
        }
    }

    fun getLogs(): List<String> {
        return logQueue.toList()
    }

    fun exportLogs(context: Context): String {
        val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val formattedFileNameDate = synchronized(fileNameFormat) { fileNameFormat.format(Date()) }
        val fileName = "screen_cast_log_${formattedFileNameDate}.txt"
        val logList = getLogs()
        val exportTimeStr = synchronized(dateFormat) { dateFormat.format(Date()) }
        val content = StringBuilder().apply {
            append("==================================================\n")
            append("QuestCast Diagnostics Log Export\n")
            append("Export Time: $exportTimeStr\n")
            append("Total Log Lines: ${logList.size}\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
            append("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
            append("==================================================\n\n")
            for (logLine in logList) {
                append(logLine).append("\n")
            }
        }.toString()

        val savedPaths = mutableListOf<String>()

        // MediaStore API for public Downloads folder on Android 10+ (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { os ->
                        os.write(content.toByteArray())
                    }
                    savedPaths.add("内部存储/Download/$fileName")
                }
            } catch (e: Exception) {
                e("AppLogger", "Failed to save log via MediaStore to Downloads", e)
            }
        } else {
            // Android 9 and lower
            try {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val targetFile = File(dir, fileName)
                targetFile.writeText(content)
                savedPaths.add(targetFile.absolutePath)
                MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf("text/plain"), null)
            } catch (e: Exception) {
                e("AppLogger", "Failed to save log to Downloads directory", e)
            }
        }

        return if (savedPaths.isNotEmpty()) {
            i("AppLogger", "Logs exported successfully to: ${savedPaths.joinToString()}")
            "日志已成功导出至:\n" + savedPaths.joinToString("\n")
        } else {
            e("AppLogger", "Failed to export logs to any location")
            "导出日志失败，无法写入存储。"
        }
    }
}

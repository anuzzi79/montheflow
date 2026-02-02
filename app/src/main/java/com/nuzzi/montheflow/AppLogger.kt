package com.nuzzi.montheflow

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val TAG = "AppLogger"
    private var logFile: File? = null
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        val dir = context.filesDir
        logFile = File(dir, "openai_flow.log")
        log("INIT", "Logger initialized at ${logFile?.absolutePath}")
    }

    fun log(tag: String, message: String) {
        val timestamp = formatter.format(Date())
        val line = "[$timestamp] [$tag] $message\n"
        Log.d(TAG, line.trim())
        val file = logFile ?: return
        synchronized(lock) {
            try {
                file.appendText(line)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log", e)
            }
        }
    }

    fun getLogPath(): String? {
        return logFile?.absolutePath
    }
}

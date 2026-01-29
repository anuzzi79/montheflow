package com.nuzzi.montheflow

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class TranscriptSaver(private val context: Context) {

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var currentFileUri: android.net.Uri? = null
    private var sessionActive: Boolean = false

    fun ensureSessionStarted() {
        runIo {
            if (sessionActive && currentFileUri != null) return@runIo
            startNewSessionLocked()
        }
    }

    fun endSession(reason: String = "Session ended") {
        runIo {
            if (!sessionActive) return@runIo
            try {
                writeLineLocked("--- $reason ---")
            } catch (_: Exception) {}
            currentFileUri = null
            sessionActive = false
        }
    }
    
    fun startNewSession() {
        runIo {
            startNewSessionLocked()
        }
    }

    private fun startNewSessionLocked() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val filename = "Montheflow_Session_$timestamp.txt"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/Montheflow")
        }

        try {
            currentFileUri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            sessionActive = currentFileUri != null
            if (sessionActive) {
                writeLineLocked("--- Session Started: $timestamp ---")
                Log.d(TAG, "Created transcript file: $filename")
            } else {
                Log.e(TAG, "Error creating transcript file: null Uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating transcript file", e)
        }
    }

    fun append(text: String) {
        runIo {
            if (!sessionActive || currentFileUri == null) {
                startNewSessionLocked()
            }
            if (!sessionActive || currentFileUri == null) return@runIo
            writeLineLocked(text)
        }
    }

    private fun writeLineLocked(text: String) {
        try {
            // "wa" mode opens for writing and appending
            val os: OutputStream? = context.contentResolver.openOutputStream(currentFileUri!!, "wa")
            os?.use {
                it.write("$text\n".toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to transcript file", e)
        }
    }

    private fun runIo(task: () -> Unit) {
        ioExecutor.execute {
            try {
                task()
            } catch (e: Exception) {
                Log.e(TAG, "Transcript I/O error", e)
            }
        }
    }

    companion object {
        private const val TAG = "TranscriptSaver"
    }
}

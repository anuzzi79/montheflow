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

class TranscriptSaver(private val context: Context) {

    private var currentFileUri: android.net.Uri? = null
    
    fun startNewSession() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val filename = "Montheflow_Session_$timestamp.txt"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/Montheflow")
        }

        try {
            currentFileUri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            append("--- Session Started: $timestamp ---\n")
            Log.d(TAG, "Created transcript file: $filename")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating transcript file", e)
        }
    }

    fun append(text: String) {
        if (currentFileUri == null) return

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

    companion object {
        private const val TAG = "TranscriptSaver"
    }
}

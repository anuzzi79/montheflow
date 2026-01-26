package com.nuzzi.montheflow

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class TranscriptDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcript_detail)

        val filePath = intent.getStringExtra("FILE_PATH") ?: return
        val textView = findViewById<TextView>(R.id.textContent)

        try {
            val content = File(filePath).readText()
            textView.text = content
        } catch (e: Exception) {
            textView.text = "Error reading file: ${e.message}"
        }
    }
}

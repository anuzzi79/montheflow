package com.nuzzi.montheflow

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnOpenFolder = findViewById<Button>(R.id.btnOpenFolder)

        // Carica la chiave esistente se c'è
        val prefs = getSharedPreferences("MontheflowPrefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("ASSEMBLYAI_KEY", "")
        apiKeyInput.setText(savedKey)

        btnSave.setOnClickListener {
            val newKey = apiKeyInput.text.toString().trim()
            if (newKey.isNotEmpty()) {
                prefs.edit().putString("ASSEMBLYAI_KEY", newKey).apply()
                Toast.makeText(this, "API Key Saved!", Toast.LENGTH_SHORT).show()
                finish() // Torna alla Main Activity
            } else {
                Toast.makeText(this, "Please enter a valid API Key", Toast.LENGTH_SHORT).show()
            }
        }

        btnOpenFolder.setOnClickListener {
            openTranscriptsFolder()
        }
    }

    private fun openTranscriptsFolder() {
        try {
            // Apri la History Activity interna
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open History", Toast.LENGTH_SHORT).show()
        }
    }
}

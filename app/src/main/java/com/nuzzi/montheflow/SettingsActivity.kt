package com.nuzzi.montheflow

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val apiKeyLabel = findViewById<TextView>(R.id.apiKeyLabel)
        val apiKeyInputLayout = findViewById<TextInputLayout>(R.id.apiKeyInputLayout)
        val openaiKeyInput = findViewById<EditText>(R.id.openaiKeyInput)
        val openaiKeyLabel = findViewById<TextView>(R.id.openaiKeyLabel)
        val openaiKeyInputLayout = findViewById<TextInputLayout>(R.id.openaiKeyInputLayout)
        val switchOpenAI = findViewById<SwitchMaterial>(R.id.switchOpenAI)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnOpenFolder = findViewById<Button>(R.id.btnOpenFolder)

        val prefs = getSharedPreferences("MontheflowPrefs", Context.MODE_PRIVATE)
        val savedAssemblyKey = prefs.getString("ASSEMBLYAI_KEY", "")
        val savedOpenAIKey = prefs.getString("OPENAI_KEY", "")
        val useOpenAI = prefs.getBoolean("USE_OPENAI", false)

        apiKeyInput.setText(savedAssemblyKey)
        openaiKeyInput.setText(savedOpenAIKey)
        switchOpenAI.isChecked = useOpenAI

        fun updateVisibility(openAIEnabled: Boolean) {
            val openaiVisibility = if (openAIEnabled) android.view.View.VISIBLE else android.view.View.GONE
            val assemblyVisibility = if (openAIEnabled) android.view.View.GONE else android.view.View.VISIBLE
            openaiKeyLabel.visibility = openaiVisibility
            openaiKeyInputLayout.visibility = openaiVisibility
            apiKeyLabel.visibility = assemblyVisibility
            apiKeyInputLayout.visibility = assemblyVisibility
        }

        updateVisibility(useOpenAI)

        switchOpenAI.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("USE_OPENAI", isChecked).apply()
            updateVisibility(isChecked)
        }

        btnSave.setOnClickListener {
            val openAIEnabled = switchOpenAI.isChecked
            if (openAIEnabled) {
                val newKey = openaiKeyInput.text.toString().trim()
                if (newKey.isNotEmpty()) {
                    prefs.edit()
                        .putString("OPENAI_KEY", newKey)
                        .putBoolean("USE_OPENAI", true)
                        .apply()
                    Toast.makeText(this, "OpenAI API Key Saved!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "Please enter a valid OpenAI API Key", Toast.LENGTH_SHORT).show()
                }
            } else {
                val newKey = apiKeyInput.text.toString().trim()
                if (newKey.isNotEmpty()) {
                    prefs.edit()
                        .putString("ASSEMBLYAI_KEY", newKey)
                        .putBoolean("USE_OPENAI", false)
                        .apply()
                    Toast.makeText(this, "AssemblyAI API Key Saved!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "Please enter a valid AssemblyAI API Key", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnOpenFolder.setOnClickListener {
            openTranscriptsFolder()
        }
    }

    private fun openTranscriptsFolder() {
        try {
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open History", Toast.LENGTH_SHORT).show()
        }
    }
}

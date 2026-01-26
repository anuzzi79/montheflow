package com.nuzzi.montheflow

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var adapter: HistoryAdapter
    private val filesList = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        val emptyView = findViewById<TextView>(R.id.emptyView)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = HistoryAdapter(filesList, 
            onItemClick = { file ->
                val intent = Intent(this, TranscriptDetailActivity::class.java)
                intent.putExtra("FILE_PATH", file.absolutePath)
                startActivity(intent)
            },
            onShareClick = { file -> shareFile(file) },
            onDeleteClick = { file -> confirmDelete(file) }
        )
        recyclerView.adapter = adapter

        loadFiles()
    }

    private fun loadFiles() {
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Montheflow")
        
        filesList.clear()
        folder.listFiles { file -> file.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.let { filesList.addAll(it) }

        adapter.notifyDataSetChanged()
        
        val emptyView = findViewById<TextView>(R.id.emptyView)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        
        if (filesList.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun shareFile(file: File) {
        try {
            // Usa FileProvider per condividere in modo sicuro (Android 7+)
            // Nota: Richiede configurazione provider nel Manifest, per ora usiamo text share semplice
            // se il file è piccolo, altrimenti uri.
            
            val content = file.readText()
            val shareIntent = Intent(Intent.ACTION_SEND)
            shareIntent.type = "text/plain"
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Transcript: ${file.name}")
            shareIntent.putExtra(Intent.EXTRA_TEXT, content)
            startActivity(Intent.createChooser(shareIntent, "Share Transcript"))
        } catch (e: Exception) {
            Toast.makeText(this, "Error sharing file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete File")
            .setMessage("Are you sure you want to delete ${file.name}?")
            .setPositiveButton("Delete") { _, _ ->
                if (file.delete()) {
                    Toast.makeText(this, "File deleted", Toast.LENGTH_SHORT).show()
                    loadFiles()
                } else {
                    Toast.makeText(this, "Could not delete file", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

class HistoryAdapter(
    private val files: List<File>,
    private val onItemClick: (File) -> Unit,
    private val onShareClick: (File) -> Unit,
    private val onDeleteClick: (File) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val filename: TextView = view.findViewById(R.id.filename)
        val fileDate: TextView = view.findViewById(R.id.fileDate)
        val btnShare: ImageButton = view.findViewById(R.id.btnShare)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.filename.text = file.name
        
        val date = Date(file.lastModified())
        val format = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        holder.fileDate.text = format.format(date)

        holder.itemView.setOnClickListener { onItemClick(file) }
        holder.btnShare.setOnClickListener { onShareClick(file) }
        holder.btnDelete.setOnClickListener { onDeleteClick(file) }
    }

    override fun getItemCount() = files.size
}

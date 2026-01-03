package com.seeker.seekprivacy

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class FileAdapter(private var fileList: List<File>, private val onItemClick: (File) -> Unit) :
    RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val fileNameTextView: TextView = itemView.findViewById(R.id.fileNameText)
        val fileIcon: ImageView = itemView.findViewById(R.id.fileIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
    var file = fileList[position]
    holder.fileNameTextView.text = file.name

    when {
        file.isDirectory -> {
            holder.fileIcon.setImageResource(R.drawable.ic_folder)
        }
        file.name.endsWith(".enc") -> {
            holder.fileIcon.setImageResource(R.drawable.ic_lock)
        }
        else -> {

            // for .jpg, .pdf, .mp4, etc. 
            // For now, the default file icon is perfect.
            holder.fileIcon.setImageResource(R.drawable.ic_file)
        }
    }

    holder.itemView.setOnClickListener { onItemClick(file) }
}

    override fun getItemCount(): Int = fileList.size
    
    fun updateData(newList: List<File>) {
    this.fileList = newList // Replace the old list with the filtered one
    notifyDataSetChanged()  // Tellz the UI to redraw the list immediately
}
}


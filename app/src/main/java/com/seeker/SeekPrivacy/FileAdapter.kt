package com.seeker.seekprivacy

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class FileAdapter(
    private val fileList: List<File>,
    private val onItemClick: (File) -> Unit
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val fileNameTextView: TextView = itemView.findViewById(R.id.fileNameText)
        val fileIcon: ImageView = itemView.findViewById(R.id.fileIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = fileList[position]
        holder.fileNameTextView.text = file.name
        holder.fileIcon.setImageResource(
            if (file.name.endsWith(".enc")) R.drawable.ic_lock else R.drawable.ic_file
        )

        holder.itemView.setOnClickListener { onItemClick(file) }

        holder.itemView.setOnLongClickListener {
            val popup = PopupMenu(holder.itemView.context, holder.itemView)
            popup.menuInflater.inflate(R.menu.file_options_menu, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.openFile -> {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.fromFile(file), "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        holder.itemView.context.startActivity(intent)
                        true
                    }
                    R.id.encryptAgain -> {
                        onItemClick(file)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
            true
        }
    }

    override fun getItemCount(): Int = fileList.size
}


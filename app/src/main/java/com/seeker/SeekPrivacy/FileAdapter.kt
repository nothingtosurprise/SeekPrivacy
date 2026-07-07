package com.seeker.seekprivacy

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import com.bumptech.glide.Glide

class FileAdapter(
    private var fileList: List<File>, 
    private val onItemClick: (File) -> Unit,

    private val onSelectionChanged: (hasSelection: Boolean) -> Unit 
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {


    val selectedFiles = mutableSetOf<File>()
    

    val isSelectionMode: Boolean get() = selectedFiles.isNotEmpty()

    class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val fileNameTextView: TextView = itemView.findViewById(R.id.fileNameText)
        val fileIcon: ImageView = itemView.findViewById(R.id.fileIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
    val file = fileList[position]
    holder.fileNameTextView.text = file.name

    // --- Selection UI ---
    if (selectedFiles.contains(file)) {
        holder.itemView.setBackgroundColor(Color.parseColor("#33009688"))
    } else {
        holder.itemView.setBackgroundColor(Color.TRANSPARENT) 
    }


    Glide.with(holder.itemView.context).clear(holder.fileIcon)


    val typedValue = android.util.TypedValue()
    holder.itemView.context.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
    val orangeTintList = android.content.res.ColorStateList.valueOf(typedValue.data)

    val extension = file.extension.lowercase()
    val isImage = extension in listOf("jpg", "jpeg", "png", "webp", "heic")
    val isVideo = extension in listOf("mp4", "mkv", "mov", "avi", "3gp", "webm")


    Glide.with(holder.itemView.context).clear(holder.fileIcon)

    when {
        file.isDirectory -> {
            holder.fileIcon.imageTintList = orangeTintList 
            holder.fileIcon.setImageResource(R.drawable.ic_folder)
        }
        file.name.endsWith(".enc") -> {
            holder.fileIcon.imageTintList = orangeTintList 
            holder.fileIcon.setImageResource(R.drawable.ic_lock)
        }
        isImage -> {

            holder.fileIcon.imageTintList = null

            Glide.with(holder.itemView.context)
                .asBitmap()
                .load(file.readBytes()) 
                .override(200, 200)
                .centerCrop()
                .placeholder(R.drawable.ic_file)
                .error(R.drawable.ic_file)
                .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.Bitmap> {
                    override fun onLoadFailed(
                        e: com.bumptech.glide.load.engine.GlideException?,
                        model: Any?,
                        target: com.bumptech.glide.request.target.Target<android.graphics.Bitmap>?,
                        isFirstResource: Boolean
                    ): Boolean {

                        holder.fileIcon.imageTintList = orangeTintList
                        return false 
                    }

                    override fun onResourceReady(
                        resource: android.graphics.Bitmap?,
                        model: Any?,
                        target: com.bumptech.glide.request.target.Target<android.graphics.Bitmap>?,
                        dataSource: com.bumptech.glide.load.DataSource?,
                        isFirstResource: Boolean
                    ): Boolean {
                        return false
                    }
                })
                .into(holder.fileIcon)
        }
        isVideo -> {

            holder.fileIcon.imageTintList = null

            Glide.with(holder.itemView.context)
                .asBitmap()
                .load(java.io.File(file.absolutePath)) 
                .frame(1000000)
                .override(200, 200)
                .centerCrop()
                .placeholder(R.drawable.ic_file)
                .error(R.drawable.ic_file)
                .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.Bitmap> {
                    override fun onLoadFailed(
                        e: com.bumptech.glide.load.engine.GlideException?,
                        model: Any?,
                        target: com.bumptech.glide.request.target.Target<android.graphics.Bitmap>?,
                        isFirstResource: Boolean
                    ): Boolean {

                        holder.fileIcon.imageTintList = orangeTintList
                        return false
                    }

                    override fun onResourceReady(
                        resource: android.graphics.Bitmap?,
                        model: Any?,
                        target: com.bumptech.glide.request.target.Target<android.graphics.Bitmap>?,
                        dataSource: com.bumptech.glide.load.DataSource?,
                        isFirstResource: Boolean
                    ): Boolean {
                        return false
                    }
                })
                .into(holder.fileIcon)
        }
        else -> {
            holder.fileIcon.imageTintList = orangeTintList 
            holder.fileIcon.setImageResource(R.drawable.ic_file)
        }
    }
        
        

    holder.itemView.setOnClickListener {
        if (isSelectionMode) {
            toggleSelection(file) 
        } else {
            onItemClick(file)
        }
    }

    holder.itemView.setOnLongClickListener {
        if (!isSelectionMode) {
            toggleSelection(file) 
        } else {
            onItemClick(file) 
        }
        true
    }
}

    override fun getItemCount(): Int = fileList.size

    fun updateData(newList: List<File>) {
        this.fileList = newList
        notifyDataSetChanged()
    }


    
    private fun toggleSelection(file: File) {
        if (selectedFiles.contains(file)) {
            selectedFiles.remove(file)
        } else {
            selectedFiles.add(file)
        }
        

        notifyDataSetChanged() 
        

        onSelectionChanged(isSelectionMode)
    }

    fun clearSelection() {
        selectedFiles.clear()
        notifyDataSetChanged()
        onSelectionChanged(false)
    }
}

package com.seeker.seekprivacy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.util.*
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import androidx.core.content.FileProvider
import android.webkit.MimeTypeMap

class FolderFilesActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var addFileFab: FloatingActionButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var sharedPreferences: SharedPreferences
    private var progressDialog: AlertDialog? = null

    private val PREFS_NAME = "EncryptPrefs"
    private val KEY_VERIFICATION = "verificationKey"

    private lateinit var verificationString: String
    private val encryptedFiles = mutableListOf<File>()
    private lateinit var fileAdapter: FileAdapter
    private var isEncryptedFolder: Boolean = true
    private lateinit var openDocumentLauncher: ActivityResultLauncher<Intent>

    private val encryptedDir = File(Environment.getExternalStorageDirectory(), "Encrypted")
    private val decryptedDir = File(Environment.getExternalStorageDirectory(), "Decrypted")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files_list)

        toolbar = findViewById(R.id.topAppBar)
        addFileFab = findViewById(R.id.addFileFab)
        recyclerView = findViewById(R.id.filesRecyclerView)
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        isEncryptedFolder = intent.getBooleanExtra("isEncryptedFolder", true)
        verificationString = intent.getStringExtra("verificationString")
            ?: sharedPreferences.getString(KEY_VERIFICATION, null)
            ?: run {
                Toast.makeText(this, "Verification string missing", Toast.LENGTH_LONG).show()
                finish()
                return
            }

        toolbar.title = if (isEncryptedFolder) "Encrypted Files" else "Decrypted Files"
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        fileAdapter = FileAdapter(encryptedFiles) { file ->
            if (isEncryptedFolder) promptDecryptFile(file) else promptEncryptFile(file)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = fileAdapter

        addFileFab.setOnClickListener { openFilePicker() }

        openDocumentLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.let { data ->
                        val clipData = data.clipData
                        if (clipData != null) {
                            for (i in 0 until clipData.itemCount) {
                                val uri = clipData.getItemAt(i).uri
                                lifecycleScope.launch {
                                    if (isEncryptedFolder) encryptFileUri(uri) else decryptFileUri(uri)
                                }
                            }
                        } else {
                            data.data?.let { uri ->
                                lifecycleScope.launch {
                                    if (isEncryptedFolder) encryptFileUri(uri) else decryptFileUri(uri)
                                }
                            }
                        }
                    }
                }
            }

        loadFileList()
    }

    private fun showLoadingDialog(message: String) {
        val progressView = layoutInflater.inflate(R.layout.dialog_loading, null)
        progressView.findViewById<TextView>(R.id.loadingMessage).text = message
        progressDialog = AlertDialog.Builder(this)
            .setView(progressView)
            .setCancelable(false)
            .create()
        progressDialog?.show()
    }

    private fun hideLoadingDialog() {
        progressDialog?.dismiss()
    }

    private fun loadFileList() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dir = if (isEncryptedFolder) encryptedDir else decryptedDir
            if (!dir.exists()) dir.mkdirs()
            val files = dir.listFiles()?.toList() ?: emptyList()
            val filteredFiles = if (isEncryptedFolder) {
                files.filter { it.isFile && it.name.endsWith(".enc") }
            } else {
                files.filter { it.isFile && !it.name.endsWith(".enc") }
            }
            encryptedFiles.clear()
            encryptedFiles.addAll(filteredFiles)
            withContext(Dispatchers.Main) { fileAdapter.notifyDataSetChanged() }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        openDocumentLauncher.launch(intent)
    }

    private suspend fun encryptFileUri(uri: Uri) {
        showLoadingDialog("Encrypting file...")
        withContext(Dispatchers.IO) {
            try {
                val path = getRealPathFromUri(uri)
                if (path != null) {
                    val file = File(path)
                    val outputFile = File(encryptedDir, "${file.name}.enc")
                    encryptFile(file, outputFile)
                    if (file.exists()) file.delete()
                    loadFileList()
                } else {
                    runOnUiThread {
                        Toast.makeText(this@FolderFilesActivity, "Cannot get real file path", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@FolderFilesActivity, "Encryption failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        hideLoadingDialog()
    }

    private suspend fun decryptFileUri(uri: Uri) {
        showLoadingDialog("Decrypting file...")
        withContext(Dispatchers.IO) {
            try {
                val path = getRealPathFromUri(uri)
                if (path != null) {
                    val file = File(path)
                    val outputFile = File(decryptedDir, file.name.removeSuffix(".enc"))
                    decryptFile(file, outputFile)
                    if (file.exists()) file.delete()
                    loadFileList()
                } else {
                    runOnUiThread {
                        Toast.makeText(this@FolderFilesActivity, "Cannot get real file path", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@FolderFilesActivity, "Decryption failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        hideLoadingDialog()
    }

    private fun encryptFile(inputFile: File, outputFile: File) {
        val key = loadKeyFromAssets("publicKey1.key")
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        FileInputStream(inputFile).use { fis ->
            FileOutputStream(outputFile).use { fos ->
                CipherOutputStream(fos, cipher).use { cos -> fis.copyTo(cos) }
            }
        }
    }

    private fun decryptFile(inputFile: File, outputFile: File) {
        val key = loadKeyFromAssets("publicKey1.key")
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.DECRYPT_MODE, key)
        FileInputStream(inputFile).use { fis ->
            FileOutputStream(outputFile).use { fos ->
                CipherInputStream(fis, cipher).use { cis -> cis.copyTo(fos) }
            }
        }
    }

    private fun getRealPathFromUri(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val docId = DocumentsContract.getDocumentId(uri)
            val split = docId.split(":")
            val type = split[0]
            val pathPart = split.getOrNull(1)
            if (type.equals("primary", true) && pathPart != null) {
                return "${Environment.getExternalStorageDirectory()}/$pathPart"
            }
        }
        return null
    }

    private fun promptDecryptFile(file: File) {
        val input = android.widget.EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("Decrypt File")
            .setMessage("Enter the verification string to decrypt.")
            .setView(input)
            .setPositiveButton("Decrypt") { dialog, _ ->
                if (input.text.toString() == verificationString) {
                    dialog.dismiss()
                    lifecycleScope.launch { 
                        decryptFile(file, File(decryptedDir, file.name.removeSuffix(".enc"))) 
                        if (file.exists()) file.delete()
                        loadFileList()
                    }
                } else {
                    Toast.makeText(this, "Incorrect verification string.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Open") { dialog, _ ->
                dialog.dismiss()
                try {
                    val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
                    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (e: Exception) {
                    Toast.makeText(this, "No app available to open this file", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun promptEncryptFile(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Encrypt File")
            .setMessage("Do you want to encrypt this file again?")
            .setPositiveButton("Encrypt") { dialog, _ ->
                dialog.dismiss()
                lifecycleScope.launch {
                    encryptFile(file, File(encryptedDir, "${file.name}.enc"))
                    if (file.exists()) file.delete()
                    loadFileList()
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Open") { dialog, _ ->
                dialog.dismiss()
                try {
                    val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
                    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (e: Exception) {
                    Toast.makeText(this, "No app available to open this file", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun loadKeyFromAssets(filename: String): SecretKey {
        val base64Key = assets.open(filename).bufferedReader().use { it.readText() }
        val keyBytes = Base64.getDecoder().decode(base64Key)
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun queryFileName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return null
    }
}


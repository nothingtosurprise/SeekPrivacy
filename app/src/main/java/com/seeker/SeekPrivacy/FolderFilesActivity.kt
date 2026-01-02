package com.seeker.seekprivacy

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import java.security.SecureRandom
import javax.crypto.spec.IvParameterSpec


class FolderFilesActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var addFileFab: FloatingActionButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var fileAdapter: FileAdapter
    
    // Keep these! They define the physical storage locations
private val encryptedDir by lazy { File(getExternalFilesDir(null), "Encrypted") }
private val decryptedDir by lazy { File(getExternalFilesDir(null), "Decrypted") }

// Add these to manage navigation
private lateinit var rootDir: File
private lateinit var currentDir: File

    private var progressDialog: AlertDialog? = null
    private val encryptedFiles = mutableListOf<File>()
    private var isEncryptedFolder: Boolean = true
    private lateinit var verificationString: String
    private lateinit var openDocumentLauncher: ActivityResultLauncher<Intent>


    private val KEYSTORE_ALIAS = "seekPrivacyRSAKey"
    private val LAST_SURVIVAL_FILE = ".lastsurvival"
    private lateinit var masterKey: SecretKey

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files_list)

        toolbar = findViewById(R.id.topAppBar)
        addFileFab = findViewById(R.id.addFileFab)
        recyclerView = findViewById(R.id.filesRecyclerView)

        isEncryptedFolder = intent.getBooleanExtra("isEncryptedFolder", true)
        rootDir = if (isEncryptedFolder) encryptedDir else decryptedDir
        currentDir = rootDir
        verificationString = intent.getStringExtra("verificationString") ?: run {
            Toast.makeText(this, "Verification string missing", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        toolbar.title = if (isEncryptedFolder) "Encrypted Files" else "Decrypted Files"
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        toolbar.inflateMenu(R.menu.files_menu) // Create a menu with a "New Folder" icon
        toolbar.setOnMenuItemClickListener {
           if (it.itemId == R.id.action_new_folder) {
           showNewFolderDialog()
            true
           } else false
}

        fileAdapter = FileAdapter(encryptedFiles) { file ->
    if (file.isDirectory) {
        currentDir = file // Set the new current directory
        loadFileList()    // Refresh the screen to show what's inside
    } else {
        // This is where you'll call your new options dialog (see below)
        showFileOptionsDialog(file) 
    }
}

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = fileAdapter

        addFileFab.setOnClickListener { openFilePicker() }

        lifecycleScope.launch(Dispatchers.IO) {
            masterKey = loadOrCreateMasterKey()
        }

        openDocumentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
        result.data?.let { data ->
            val uris = mutableListOf<Uri>()
            
            // Correctly handles single or multiple file selection
            val clipData = data.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    uris.add(clipData.getItemAt(i).uri)
                }
            } else {
                data.data?.let { uris.add(it) }
            }

            // Starts ONE coroutine and ONE dialog for the whole group
            lifecycleScope.launch {
                showLoadingDialog("Processing ${uris.size} files...")
                
                uris.forEach { uri ->
                    if (isEncryptedFolder) {
                        encryptFileUri(uri, isBatch = true) 
                    } else {
                        decryptFileUri(uri, isBatch = true)
                    }
                }
                
                // Refresh list and hide dialog ONLY once at the very end
                loadFileList() 
                hideLoadingDialog()
            }
        }
    }
}

    // -------------------- KEY MANAGEMENT --------------------
    private fun loadOrCreateMasterKey(): SecretKey {
        val survivalFile = getLastSurvivalFile()
        if (survivalFile.exists()) {
            val wrappedKeyBase64 = survivalFile.readText()
            val wrappedKeyBytes = Base64.getDecoder().decode(wrappedKeyBase64)
            return unwrapAESKey(wrappedKeyBytes)
        } else {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            val newAESKey = keyGen.generateKey()
            val wrapped = wrapAESKey(newAESKey)
            saveWrappedKey(wrapped)
            return newAESKey
        }
    }

    private fun getLastSurvivalFile(): File {
        val dir = if (isEncryptedFolder) encryptedDir else decryptedDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, LAST_SURVIVAL_FILE)
    }

    private fun saveWrappedKey(wrappedKey: ByteArray) {
        val base64 = Base64.getEncoder().encodeToString(wrappedKey)
        listOf(File(encryptedDir, LAST_SURVIVAL_FILE), File(decryptedDir, LAST_SURVIVAL_FILE)).forEach {
            if (!it.parentFile.exists()) it.parentFile.mkdirs()
            it.writeText(base64)
        }
    }

    private fun wrapAESKey(aesKey: SecretKey): ByteArray {
        val rsaKey = getOrCreateRSAKeyPair()
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.WRAP_MODE, rsaKey.public)
        return cipher.wrap(aesKey)
    }

    private fun unwrapAESKey(wrappedKey: ByteArray): SecretKey {
        val rsaKey = getOrCreateRSAKeyPair()
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.UNWRAP_MODE, rsaKey.private)
        return cipher.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY) as SecretKey
    }

    private fun getOrCreateRSAKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val kpg = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
            kpg.initialize(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                            android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                ).setKeySize(2048)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .build()
            )
            kpg.generateKeyPair()
        }
        val privateKey = keyStore.getKey(KEYSTORE_ALIAS, null) as java.security.PrivateKey
        val publicKey = keyStore.getCertificate(KEYSTORE_ALIAS).publicKey
        return KeyPair(publicKey, privateKey)
    }

    // -------------------- FILE OPERATIONS --------------------
    private fun showLoadingDialog(message: String) {
        val progressView = layoutInflater.inflate(R.layout.dialog_loading, null)
        progressView.findViewById<TextView>(R.id.loadingMessage).text = message
        progressDialog = AlertDialog.Builder(this)
            .setView(progressView)
            .setCancelable(false)
            .create()
        progressDialog?.show()
    }

    private fun hideLoadingDialog() { progressDialog?.dismiss() }

    private fun loadFileList() {
    lifecycleScope.launch(Dispatchers.IO) {
        if (!currentDir.exists()) currentDir.mkdirs()
        
        // List everything, but ignore the ".lastsurvival" key file
        val allFiles = currentDir.listFiles()?.filter { it.name != LAST_SURVIVAL_FILE } ?: emptyList()
        
        // Sort: Folders first, then Files
        val sortedList = allFiles.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        withContext(Dispatchers.Main) {
            encryptedFiles.clear()
            encryptedFiles.addAll(sortedList)
            fileAdapter.notifyDataSetChanged()
            
            // Update toolbar subtitle to show current path relative to root
            toolbar.subtitle = currentDir.absolutePath.removePrefix(rootDir.absolutePath)
        }
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

    private suspend fun encryptFileUri(uri: Uri, isBatch: Boolean = false) {
    if (!isBatch) showLoadingDialog("Encrypting file...")
    try {
        withContext(Dispatchers.IO) {
            val inputStream = contentResolver.openInputStream(uri) ?: return@withContext
            val fileName = queryFileName(uri) ?: "unknownfile"
            
            // FIX: Saves to your current folder location
            val outputFile = File(currentDir, "$fileName.enc")
            
            encryptFile(inputStream, outputFile)
            deleteExternalFile(uri)
        }
        
        // Plz MUST BE AFTER the IO block (Your original UI stability logic)
        if (!isBatch) loadFileList()

    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@FolderFilesActivity, "Encryption failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    if (!isBatch) hideLoadingDialog()
}


    private suspend fun decryptFileUri(uri: Uri, isBatch: Boolean = false) {
    if (!isBatch) showLoadingDialog("Decrypting file...")
    var hadError = false

    withContext(Dispatchers.IO) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return@withContext
            val fileName = queryFileName(uri)?.removeSuffix(".enc") ?: "unknownfile"
            
            // FIX: Saves to your current folder location
            val outputFile = File(currentDir, fileName)

            decryptFile(inputStream, outputFile)
            deleteExternalFile(uri)
        } catch (e: Exception) {
            hadError = true
            withContext(Dispatchers.Main) {
                Toast.makeText(this@FolderFilesActivity, "Decryption failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Plz MUST be outside IO block (Your original UI stability logic)
    if (!hadError && !isBatch) {
        loadFileList()
    }
    if (!isBatch) hideLoadingDialog()
}


    private fun encryptFile(inputStream: InputStream, outputFile: File) {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")

    val iv = ByteArray(16)
    SecureRandom().nextBytes(iv)

    cipher.init(Cipher.ENCRYPT_MODE, masterKey, IvParameterSpec(iv))

    FileOutputStream(outputFile).use { fos ->
        fos.write(iv) // prepend IV
        CipherOutputStream(fos, cipher).use { cos ->
            inputStream.copyTo(cos)
        }
    }
}


    private fun decryptFile(inputStream: InputStream, outputFile: File) {
    val iv = ByteArray(16)
    inputStream.read(iv)

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, masterKey, IvParameterSpec(iv))

    FileOutputStream(outputFile).use { fos ->
        CipherInputStream(inputStream, cipher).use { cis ->
            cis.copyTo(fos)
        }
    }
}


    private fun promptDecryptFile(file: File) {
    val input = android.widget.EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
    AlertDialog.Builder(this)
        .setTitle("Decrypt File")
        .setMessage("Enter verification string to decrypt")
        .setView(input)
        .setPositiveButton("Decrypt") { dialog, _ ->
            if (input.text.toString() == verificationString) {
                dialog.dismiss()
                showLoadingDialog("Decrypting...") // Show progress
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { // Move to background thread
                        // FIX: Changed decryptedDir to currentDir
                        val decryptedFile = File(currentDir, file.name.removeSuffix(".enc"))
                        decryptFile(FileInputStream(file), decryptedFile)
                        file.delete()
                    }
                    loadFileList() // Refresh UI
                    hideLoadingDialog()
                }
            } else Toast.makeText(this, "Incorrect verification string.", Toast.LENGTH_SHORT).show()
        }
        .setNeutralButton("Open") { dialog, _ ->
            dialog.dismiss()
            openFileWithProvider(file)
        }
        .show()
}

private fun promptEncryptFile(file: File) {
    AlertDialog.Builder(this)
        .setTitle("Encrypt File")
        .setMessage("Do you want to encrypt this file again?")
        .setPositiveButton("Encrypt") { dialog, _ ->
            dialog.dismiss()
            showLoadingDialog("Encrypting...")
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { // Move to background thread
                    // FIX: Changed encryptedDir to currentDir
                    val encryptedFile = File(currentDir, "${file.name}.enc")
                    encryptFile(FileInputStream(file), encryptedFile)
                    file.delete()
                }
                loadFileList()
                hideLoadingDialog()
            }
        }
        .setNeutralButton("Open") { dialog, _ ->
            dialog.dismiss()
            openFileWithProvider(file)
        }
        .setNegativeButton("Share") { dialog, _ ->
            dialog.dismiss()
            shareFile(file) // Use your existing shareFile function here
        }
        .show()
}

private fun deleteExternalFile(uri: Uri): Boolean {
    return try {
        // Try to request write permission if available
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    } catch (_: Exception) {
        // Ignore, some providers don't allow persistable permissions
    }.let {
        val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(this, uri)
        doc?.delete() ?: false
    }
}


    private fun openFileWithProvider(file: File) {
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

    private fun queryFileName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return null
    }
    
    private fun showNewFolderDialog() {
    val input = android.widget.EditText(this).apply { hint = "Folder Name" }
    AlertDialog.Builder(this)
        .setTitle("New Folder")
        .setView(input)
        .setPositiveButton("Create") { _, _ ->
            val newDir = File(currentDir, input.text.toString())
            if (newDir.mkdir()) loadFileList() 
            else Toast.makeText(this, "Failed to create folder", Toast.LENGTH_SHORT).show()
        }
        .show()
}

    override fun onBackPressed() {
    // If the user is in a subfolder, go back to the parent folder
    if (currentDir != rootDir) {
        currentDir = currentDir.parentFile ?: rootDir
        loadFileList()
    } else {
        // If they are already at the root, close the activity normally
        super.onBackPressed()
    }
}

  private fun showFileOptionsDialog(file: File) {
    val options = arrayOf("Open", "Rename", if (isEncryptedFolder) "Decrypt" else "Encrypt", "Share", "Delete")
    
    AlertDialog.Builder(this)
        .setTitle(file.name)
        .setItems(options) { _, which ->
            when (which) {
                0 -> openFileWithProvider(file)
                1 -> showRenameDialog(file)
                2 -> if (isEncryptedFolder) promptDecryptFile(file) else promptEncryptFile(file)
                3 -> shareFile(file)
                4 -> {
                    file.delete()
                    loadFileList()
                }
            }
        }
        .show()
}

   private fun showRenameDialog(file: File) {
    val input = android.widget.EditText(this).apply {
        setText(file.name)
        selectAll()
    }
    AlertDialog.Builder(this)
        .setTitle("Rename")
        .setView(input)
        .setPositiveButton("OK") { _, _ ->
            val newName = input.text.toString()
            if (newName.isNotEmpty()) {
                val newFile = File(file.parentFile, newName)
                if (file.renameTo(newFile)) loadFileList()
                else Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

   private fun shareFile(file: File) {
    try {
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share via"))
    } catch (e: Exception) {
        Toast.makeText(this, "Cannot share: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
}


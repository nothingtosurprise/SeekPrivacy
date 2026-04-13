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
import android.util.Base64
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
    
    
    private lateinit var searchView: androidx.appcompat.widget.SearchView
private lateinit var countTextView: TextView
private var allFilesMasterList: List<File> = listOf()
    

    private val encryptedDir by lazy { File(getExternalFilesDir(null), "Encrypted") }
    private val decryptedDir by lazy { File(getExternalFilesDir(null), "Decrypted") }

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
        searchView = findViewById(R.id.searchview)
countTextView = findViewById(R.id.count)

searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
    override fun onQueryTextSubmit(query: String?): Boolean = false

    override fun onQueryTextChange(newText: String?): Boolean {
        filterFiles(newText ?: "")
        return true
    }
})

        isEncryptedFolder = intent.getBooleanExtra("isEncryptedFolder", true)
        rootDir = if (isEncryptedFolder) encryptedDir else decryptedDir
        currentDir = rootDir
        
        // ADD IT HERE
        if (!isEncryptedFolder) {
          addFileFab.hide() 
         }
        verificationString = intent.getStringExtra("verificationString") ?: ""

        toolbar.title = if (isEncryptedFolder) "Encrypted Files" else "Decrypted Files"
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { onBackPressed() }
        
        // Setup Menu
        toolbar.inflateMenu(R.menu.menu_files)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_new_folder) {
                showNewFolderDialog()
                true
            } else false
        }

        fileAdapter = FileAdapter(encryptedFiles) { file ->
            if (file.isDirectory) {
                currentDir = file
                loadFileList()
            } else {
                showFileOptionsDialog(file)
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = fileAdapter

        addFileFab.setOnClickListener { openFilePicker() }

        lifecycleScope.launch(Dispatchers.IO) {
            masterKey = loadOrCreateMasterKey()
            withContext(Dispatchers.Main) {
                loadFileList()
            }
        }

        openDocumentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.let { data ->
                    val uris = mutableListOf<Uri>()
                    val clipData = data.clipData
                    if (clipData != null) {
                        for (i in 0 until clipData.itemCount) {
                            uris.add(clipData.getItemAt(i).uri)
                        }
                    } else {
                        data.data?.let { uris.add(it) }
                    }

                    lifecycleScope.launch {
                        showLoadingDialog("Processing ${uris.size} files...")
                        uris.forEach { uri ->
                            if (isEncryptedFolder) encryptFileUri(uri, true)
                            else decryptFileUri(uri, true)
                        }
                        loadFileList()
                        hideLoadingDialog()
                    }
                }
            }
        }
    }

    // -------------------- KEY MANAGEMENT --------------------

    private fun loadOrCreateMasterKey(): SecretKey {
        val survivalFile = getLastSurvivalFile()
        if (survivalFile.exists()) {
            val wrappedKeyBase64 = survivalFile.readText()
             val wrappedKeyBytes = Base64.decode(wrappedKeyBase64, Base64.NO_WRAP)
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
        val base64 = Base64.encodeToString(wrappedKey, Base64.NO_WRAP)
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
        
        val allFiles = currentDir.listFiles()?.filter { it.name != LAST_SURVIVAL_FILE } ?: emptyList<File>()
        
        // Sorting: Folders first, then alphabetically
        val sortedList = allFiles.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        withContext(Dispatchers.Main) {
            // 1. Update the Master List for the Search feature
            allFilesMasterList = sortedList 

            // 2. Update the Count TextView
            countTextView.text = "${sortedList.size} Files"

            // 3. Clear and add to the adapter's list
            encryptedFiles.clear()
            encryptedFiles.addAll(sortedList)
            fileAdapter.notifyDataSetChanged()

            val displayPath = currentDir.absolutePath.removePrefix(rootDir.absolutePath)
            toolbar.subtitle = if (displayPath.isEmpty()) "Root" else displayPath
            
            // 4. If there is text in the search bar, re-apply the filter
            val currentQuery = searchView.query.toString()
            if (currentQuery.isNotEmpty()) {
                filterFiles(currentQuery)
            }
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
                val outputFile = File(currentDir, "$fileName.enc")
                encryptFile(inputStream, outputFile)
                deleteExternalFile(uri)
            }
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
        if (!hadError && !isBatch) loadFileList()
        if (!isBatch) hideLoadingDialog()
    }

    private fun encryptFile(inputStream: InputStream, outputFile: File) {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, IvParameterSpec(iv))
        FileOutputStream(outputFile).use { fos ->
            fos.write(iv)
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

    

    private fun deleteExternalFile(uri: Uri): Boolean {
        return try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
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
        if (currentDir != rootDir) {
            currentDir = currentDir.parentFile ?: rootDir
            loadFileList()
        } else {
            super.onBackPressed()
        }
    }

    private fun showFileOptionsDialog(file: File) {
    // Only show specific options based on which folder we are in
    val options = if (isEncryptedFolder) {
        arrayOf("Decrypt", "Rename", "Delete", "Move to Folder")
    } else {
        arrayOf("Open", "Encrypt Again", "Share", "Rename", "Delete", "Move to folder")
    }

    AlertDialog.Builder(this)
        .setTitle(file.name)
        .setItems(options) { _, which ->
            if (isEncryptedFolder) {
                when (which) {
                    0 -> promptDecryptFile(file)
                    1 -> showRenameDialog(file)
                    2 -> { confirmDeletion(file); loadFileList() }
                    3 -> showMoveDialog(file)
                }
            } else {
                when (which) {
                    0 -> openFileWithProvider(file)
                    1 -> promptEncryptFile(file)
                    2 -> shareFile(file)
                    3 -> showRenameDialog(file)
                    4 -> { confirmDeletion(file); loadFileList() }
                    5 -> showMoveDialog(file)
                }
            }
        }
        .show()
}

private fun showMoveDialog(file: File) {
    // Explicitly name 'f' to avoid ambiguity
    val subFolders = currentDir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList<File>()
    val options = mutableListOf<String>()
    
    val isAtRoot = (currentDir.path == encryptedDir.path || currentDir.path == decryptedDir.path)
    if (!isAtRoot) {
        options.add(".. (Move to Parent Folder)")
    }
    
    // Explicitly name 'folder' to be safe
    options.addAll(subFolders.map { folder -> folder.name })

    if (options.isEmpty()) {
        Toast.makeText(this, "No folders to move to", Toast.LENGTH_SHORT).show()
        return
    }

    AlertDialog.Builder(this)
        .setTitle("Move ${file.name} to:")
        .setItems(options.toTypedArray()) { _, which ->
            val targetDir = if (!isAtRoot && which == 0) {
                currentDir.parentFile
            } else {
                val folderIndex = if (!isAtRoot) which - 1 else which
                subFolders[folderIndex]
            }

            if (targetDir != null) {
                val destFile = File(targetDir, file.name)
                if (file.renameTo(destFile)) {
                    loadFileList()
                    Toast.makeText(this, "File moved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Move failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
        .show()
}

private fun confirmDeletion(file: File) {
    AlertDialog.Builder(this)
        .setTitle("Delete ${file.name}?")
        .setMessage("This action cannot be undone. The file will be permanently removed.")
        .setPositiveButton("Delete") { _, _ ->
            if (file.delete()) {
                loadFileList()
                Toast.makeText(this, "File deleted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to delete file", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

private fun promptDecryptFile(file: File) {
    val input = android.widget.EditText(this).apply { 
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD 
    }
    AlertDialog.Builder(this)
        .setTitle("Decrypt File")
        .setView(input)
        .setPositiveButton("Decrypt") { _, _ ->
            if (input.text.toString() == verificationString) {
                showLoadingDialog("Moving to Decrypted...")
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        // MOVE: Targets the physical Decrypted directory, not currentDir
                        val targetFile = File(decryptedDir, file.name.removeSuffix(".enc"))
                        decryptFile(FileInputStream(file), targetFile)
                        file.delete()
                    }
                    loadFileList()
                    hideLoadingDialog()
                    Toast.makeText(this@FolderFilesActivity, "File moved to Decrypted Folder", Toast.LENGTH_SHORT).show()
                }
            } else Toast.makeText(this, "Wrong Password", Toast.LENGTH_SHORT).show()
        }.show()
}

private fun promptEncryptFile(file: File) {
    showLoadingDialog("Moving to Encrypted...")
    lifecycleScope.launch {
        withContext(Dispatchers.IO) {
            // MOVE: Targets the physical Encrypted directory
            val targetFile = File(encryptedDir, "${file.name}.enc")
            encryptFile(FileInputStream(file), targetFile)
            file.delete()
        }
        loadFileList()
        hideLoadingDialog()
        Toast.makeText(this@FolderFilesActivity, "File moved to Encrypted Folder", Toast.LENGTH_SHORT).show()
    }
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
    
    private fun filterFiles(query: String) {
    val filteredList = if (query.isEmpty()) {
        allFilesMasterList
    } else {
        allFilesMasterList.filter { it.name.contains(query, ignoreCase = true) }
    }
    
    // Update the list on screen
    fileAdapter.updateData(filteredList)

    // NEW: Update the count text to show search results
    if (query.isEmpty()) {
        countTextView.text = "${allFilesMasterList.size} Files"
    } else {
        countTextView.text = "${filteredList.size} of ${allFilesMasterList.size} found"
    }
}
}

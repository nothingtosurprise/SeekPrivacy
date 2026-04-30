package com.seeker.seekprivacy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.EditText
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
import java.io.*
import java.security.*
import java.security.spec.X509EncodedKeySpec
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import androidx.activity.OnBackPressedCallback

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import android.content.SharedPreferences


import com.seeker.seekprivacy.CustomPatternActivity

import android.text.InputFilter

import android.view.WindowManager




class FolderFilesActivity : AppCompatActivity() {


    private lateinit var toolbar: MaterialToolbar
    private lateinit var addFileFab: FloatingActionButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var fileAdapter: FileAdapter
    private lateinit var searchView: androidx.appcompat.widget.SearchView
    private lateinit var countTextView: TextView
    private var pendingAction: (() -> Unit)? = null 


    private val VERSION_GCM: Byte = 0x01
    private val GCM_IV_LENGTH = 12
    private val GCM_TAG_LENGTH = 128
    private val KEYSTORE_ALIAS = "seekPrivacyRSAKey"
    private val FILE_VS_FILE = ".sys_t_idx_82"
    private val SESSION_TIMEOUT: Long = 5 * 60 * 1000L 
    
    private val PREFS_NAME = "SeekPrivacyPrefs"
    private val KEY_VERIFICATION = "verificationKey"
    private var verificationChars: CharArray? = null

   
    private var lastAuthTimestamp: Long = 0
    private var allFilesMasterList: List<File> = listOf()
    private val encryptedFiles = mutableListOf<File>()
    private var isEncryptedFolder: Boolean = true
    private var verificationString: CharArray? = null

    private var masterKey: SecretKey? = null
    
    
    companion object {

    private const val IDLE_LOCK_TIMEOUT = 300000L
    private var lastTimestamp: Long = 0L
}


private val inactivityHandler = android.os.Handler(android.os.Looper.getMainLooper())
private lateinit var lockRunnable: Runnable 
private var isProcessing = false




  private val patternUnlockLauncher = registerForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
) { result ->
    when (result.resultCode) {
        RESULT_OK -> {
            
            val newPattern = result.data?.getStringExtra("PATTERN_RESULT")
            
            if (newPattern != null) {
                
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit().apply {
                    putString(KEY_VERIFICATION, newPattern)
                    putString("auth_type", "pattern") 
                    putLong("global_auth_timestamp", System.currentTimeMillis())
                    apply()
                }
                verificationString = newPattern?.toCharArray()
                lastAuthTimestamp = System.currentTimeMillis()
                Toast.makeText(this, "Pattern Updated Successfully", Toast.LENGTH_SHORT).show()
                loadFileList()
            } else {
                
                pendingAction?.invoke()
            }
            pendingAction = null
        }
        99 -> showRecoveryFlow() 
    }
}



    private val encryptedDir by lazy { File(getExternalFilesDir(null), "Encrypted") }
    private val decryptedDir by lazy { File(getExternalFilesDir(null), "Decrypted") }
    private lateinit var rootDir: File
    private lateinit var currentDir: File
    

    private val internalEncDir by lazy { File(filesDir, "Encrypted") }
    private val internalDecDir by lazy { File(filesDir, "Decrypted") }
    private lateinit var internalRootDir: File 

    private var progressDialog: AlertDialog? = null
    private lateinit var openDocumentLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files_list)
        
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        
        

    lockRunnable = Runnable { 
        if (!isProcessing) { 
            lockVault() 
        } else {

            inactivityHandler.postDelayed(lockRunnable, 60 * 1000L)
        }
    }


        toolbar = findViewById(R.id.topAppBar)
        addFileFab = findViewById(R.id.addFileFab)
        recyclerView = findViewById(R.id.filesRecyclerView)
        searchView = findViewById(R.id.searchview)
        countTextView = findViewById(R.id.count)

        isEncryptedFolder = intent.getBooleanExtra("isEncryptedFolder", true)
        val rawPass = intent.getStringExtra("verificationString") ?: ""
        verificationString = rawPass.toCharArray()


        rootDir = if (isEncryptedFolder) encryptedDir else decryptedDir
        internalRootDir = if (isEncryptedFolder) internalEncDir else internalDecDir
        

        currentDir = rootDir

        if (!isEncryptedFolder) addFileFab.visibility = View.GONE

        setupToolbar()
        setupRecyclerView()
        setupPickers()


        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentPath = currentDir.absolutePath
                

                val isAtExternalRoot = (currentPath == rootDir.absolutePath)
                val isAtInternalRoot = (currentPath == internalRootDir.absolutePath)

                if (isAtExternalRoot || isAtInternalRoot) {

                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    val parent = currentDir.parentFile
                    

                    val parentInExternal = parent?.absolutePath?.startsWith(rootDir.absolutePath) == true
                    val parentInInternal = parent?.absolutePath?.startsWith(internalRootDir.absolutePath) == true
                    
                    if (parent != null && (parentInExternal || parentInInternal)) {
                        currentDir = parent
                        loadFileList()
                    } else {

                        currentDir = rootDir
                        loadFileList()
                    }
                }
            }
        })

        
        
        

lifecycleScope.launch(Dispatchers.IO) {
    try {

        val dirs = listOf(internalEncDir, internalDecDir, encryptedDir, decryptedDir)
        dirs.forEach { if (!it.exists()) it.mkdirs() }

        
        masterKey = loadOrCreateMasterKey()

        withContext(Dispatchers.Main) {

            loadFileList()
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {

            if (e.message == "HARDWARE_LOCKED") {

                showCriticalSecurityAlert(
                    "Security Access Denied",
                    "The device hardware refused to unlock your vault. This usually means the app signature has changed or device security was modified.\n\nApp will now close."
                )
            } else {
                android.widget.Toast.makeText(
                    this@FolderFilesActivity, 
                    "Vault Error: ${e.message}", 
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
    
    
    }
    
    override fun onResume() {
    super.onResume()
    

    if (lastTimestamp != 0L && (System.currentTimeMillis() - lastTimestamp) > IDLE_LOCK_TIMEOUT) {
        lockVault()
        return 
    }


    inactivityHandler.removeCallbacks(lockRunnable)
    inactivityHandler.postDelayed(lockRunnable, IDLE_LOCK_TIMEOUT)
}

override fun onPause() {
    super.onPause()

    lastTimestamp = System.currentTimeMillis()


    inactivityHandler.removeCallbacks(lockRunnable)
}


    private fun lockVault() {

    masterKey = null 


    encryptedFiles.clear()
    

    if (::fileAdapter.isInitialized) {
        fileAdapter.notifyDataSetChanged()
    }


    verificationString?.fill('0')
    verificationString = null


    val intent = android.content.Intent(this, DashboardActivity::class.java)
    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
    
    android.widget.Toast.makeText(this, "Security Timeout: Vault Locked", android.widget.Toast.LENGTH_LONG).show()
    
    startActivity(intent)
    finish()
}



  override fun onUserInteraction() {
    super.onUserInteraction()

    inactivityHandler.removeCallbacks(lockRunnable)
    inactivityHandler.postDelayed(lockRunnable, IDLE_LOCK_TIMEOUT)
}

   private fun showCriticalSecurityAlert(title: String, message: String) {
    androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setCancelable(false)
        .setPositiveButton("Exit") { _, _ ->
            finishAffinity() 
        }
        .show()
} 
    
    
    private fun setupToolbar() {
        toolbar.title = if (isEncryptedFolder) "Encrypted Files" else "Decrypted Files"
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        toolbar.inflateMenu(R.menu.menu_files)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_new_folder) {
                showNewFolderDialog()
                true
            } else false
        }

        searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                filterFiles(newText ?: "")
                return true
            }
        })
    }

    private fun setupRecyclerView() {
        fileAdapter = FileAdapter(encryptedFiles) { file ->
            if (file.isDirectory) {
                currentDir = file
                loadFileList()
            } else {
                handleFileAccess(file)
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = fileAdapter
    }

    private fun setupPickers() {
        addFileFab.setOnClickListener { openFilePicker() }

        openDocumentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uris = mutableListOf<Uri>()
                result.data?.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } ?: result.data?.data?.let { uris.add(it) }

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

    


    private fun loadOrCreateMasterKey(): SecretKey {

    val oldLegacy = File(encryptedDir, ".lastsurvival") 
    val currentLegacy = File(encryptedDir, ".sys_t_idx_82") 
    val ghostInternal = File(filesDir, ".sys_cache_meta") 
    val ghostExternal = File(encryptedDir, ".idx_journal") 


    val survivalFile = when {
        oldLegacy.exists() -> oldLegacy
        currentLegacy.exists() -> currentLegacy
        ghostInternal.exists() -> ghostInternal
        ghostExternal.exists() -> ghostExternal
        else -> null
    }

    return if (survivalFile != null) {
        try {
            val wrappedKeyBase64 = survivalFile.readText().trim()
            val wrappedBytes = android.util.Base64.decode(wrappedKeyBase64, android.util.Base64.NO_WRAP)
            
            val unwrappedKey = unwrapAESKey(wrappedBytes)
            
            
            saveToMultipleLocations(wrappedBytes, ghostInternal, ghostExternal, currentLegacy)
            
            unwrappedKey
        } catch (e: Exception) {

            throw IllegalStateException("HARDWARE_LOCKED")
        }
    } else {

        createNewMasterKey(ghostInternal, ghostExternal, currentLegacy)
    }
}



private fun createNewMasterKey(vararg files: File): SecretKey {
    val keyGen = KeyGenerator.getInstance("AES").apply { init(256) }
    val newAESKey = keyGen.generateKey()
    val wrapped = wrapAESKey(newAESKey)
    saveToMultipleLocations(wrapped, *files)
    return newAESKey
}

private fun saveToMultipleLocations(wrapped: ByteArray, vararg files: File) {
    val encoded = android.util.Base64.encodeToString(wrapped, android.util.Base64.NO_WRAP)
    files.forEach { file ->
        try {
            file.parentFile?.mkdirs()
            file.writeText(encoded)
        } catch (e: Exception) { }
    }
}

private fun saveWrappedKey(wrappedKey: ByteArray) {
    val base64 = Base64.encodeToString(wrappedKey, Base64.NO_WRAP)
    

    File(filesDir, FILE_VS_FILE).writeText(base64)
    

    if (!encryptedDir.exists()) encryptedDir.mkdirs()
    File(encryptedDir, ".temp_meta").writeText(base64)
}


   
    private fun wrapAESKey(aesKey: SecretKey): ByteArray {
    return try {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")

        cipher.init(Cipher.WRAP_MODE, getOrCreateRSAKeyPair().public)
        cipher.wrap(aesKey)
    } catch (e: Exception) {
        android.util.Log.e("SECURITY", "Wrap failed: ${e.message}")
        throw e
    }
}

private fun unwrapAESKey(wrappedKey: ByteArray): SecretKey {
    return try {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")

        cipher.init(Cipher.UNWRAP_MODE, getOrCreateRSAKeyPair().private)
        cipher.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY) as SecretKey
    } catch (e: android.security.keystore.UserNotAuthenticatedException) {
        
        throw Exception("HARDWARE_LOCKED") 
    } catch (e: Exception) {
        android.util.Log.e("SECURITY", "Unwrap failed: ${e.message}")
        throw e
    }
}

    private fun getOrCreateRSAKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val kpg = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or 
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            ).setKeySize(2048)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                
                 .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }
        val privateKey = keyStore.getKey(KEYSTORE_ALIAS, null) as PrivateKey
        val publicKey = keyStore.getCertificate(KEYSTORE_ALIAS).publicKey
        return KeyPair(publicKey, privateKey)
    }





  private fun secureDelete(file: File): Boolean {
    if (!file.exists()) return true 
    return try {
        if (file.length() > 0) {
            val raf = java.io.RandomAccessFile(file, "rws")

            val sizeToOverwrite = file.length().toInt().coerceAtMost(1024 * 1024)
            

            val noise = ByteArray(sizeToOverwrite)
            java.security.SecureRandom().nextBytes(noise)
            
            raf.write(noise) 
            raf.close()
        }
        file.delete() 
    } catch (e: Exception) {
        file.delete() 
    }
}

   private fun loadFileList() {

    lifecycleScope.launch(Dispatchers.IO) {
        if (!currentDir.exists()) currentDir.mkdirs()


        val primaryFiles = currentDir.listFiles()?.toList() ?: emptyList()


        val isInInternal = currentDir.absolutePath.startsWith(internalRootDir.absolutePath)
        val relativePath = if (isInInternal) {
            currentDir.absolutePath.removePrefix(internalRootDir.absolutePath)
        } else {
            currentDir.absolutePath.removePrefix(rootDir.absolutePath)
        }

        val targetMirrorRoot = if (isInInternal) rootDir else internalRootDir
        val mirrorDir = File(targetMirrorRoot, relativePath)

        val extraFiles = if (mirrorDir.exists()) {
            mirrorDir.listFiles()?.toList() ?: emptyList()
        } else {
            emptyList()
        }


        val allFiles = (primaryFiles + extraFiles)
            .filter { it.name != FILE_VS_FILE && it.name != ".nomedia" && !it.name.startsWith(".") }
            .distinctBy { it.name } 


        val sortedList = allFiles.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        withContext(Dispatchers.Main) {

            allFilesMasterList = sortedList 


            countTextView.text = "${sortedList.size} Files"


            encryptedFiles.clear()
            encryptedFiles.addAll(sortedList)
            fileAdapter.notifyDataSetChanged()


            toolbar.subtitle = if (relativePath.isEmpty()) "Root" else relativePath
            

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
    try {
        withContext(Dispatchers.IO) {
            val inputStream = contentResolver.openInputStream(uri) ?: return@withContext
            val fileName = queryFileName(uri) ?: "file_${System.currentTimeMillis()}"
            val fileSize = contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: 0

            
            val relativePath = if (currentDir.absolutePath.startsWith(internalEncDir.absolutePath)) {
                currentDir.absolutePath.removePrefix(internalEncDir.absolutePath)
            } else {
                currentDir.absolutePath.removePrefix(encryptedDir.absolutePath)
            }


            val outputFile = getTargetFile("$fileName.enc", fileSize, relativePath)
            
            encryptFile(inputStream, outputFile)
            deleteExternalFile(uri)
        }
        if (!isBatch) loadFileList()
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@FolderFilesActivity, "Encryption failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

    private suspend fun decryptFileUri(uri: Uri, isBatch: Boolean = false) {
        withContext(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri) ?: return@withContext
                val fileName = queryFileName(uri)?.removeSuffix(".enc") ?: "file_${System.currentTimeMillis()}"
                val encryptedSize = contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: 0
                

                val baseDir = if (encryptedSize > 50 * 1024 * 1024) decryptedDir else File(filesDir, "Decrypted")
                if (!baseDir.exists()) baseDir.mkdirs()
                
                val outputFile = File(baseDir, fileName)
                decryptFile(inputStream, outputFile)
                deleteExternalFile(uri)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FolderFilesActivity, "Decryption failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        if (!isBatch) loadFileList()
    }

    private fun encryptFile(inputStream: InputStream, outputFile: File) {
    isProcessing = true 
    try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH).apply { java.security.SecureRandom().nextBytes(this) }
        
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        
        java.io.FileOutputStream(outputFile).use { fos ->
            fos.write(VERSION_GCM.toInt()) 
            fos.write(iv)
            javax.crypto.CipherOutputStream(fos, cipher).use { cos -> 
                inputStream.copyTo(cos) 
            }
        }
    } finally {
        isProcessing = false 
        resetInactivityTimer() 
    }
}

private fun decryptFile(inputStream: InputStream, outputFile: File) {
    isProcessing = true
    try {
        val firstByte = inputStream.read()
        
        if (firstByte == VERSION_GCM.toInt()) {
            val iv = ByteArray(GCM_IV_LENGTH).apply { inputStream.read(this) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            
            java.io.FileOutputStream(outputFile).use { fos ->
                javax.crypto.CipherInputStream(inputStream, cipher).use { cis -> 
                    cis.copyTo(fos) 
                }
            }
        } else {

            val iv = ByteArray(16).apply {
                this[0] = firstByte.toByte()
                inputStream.read(this, 1, 15)
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey, javax.crypto.spec.IvParameterSpec(iv))
            
            java.io.FileOutputStream(outputFile).use { fos ->
                javax.crypto.CipherInputStream(inputStream, cipher).use { cis -> 
                    cis.copyTo(fos) 
                }
            }
        }
    } finally {
        isProcessing = false
        resetInactivityTimer()
    }
}


private fun resetInactivityTimer() {
    inactivityHandler.removeCallbacks(lockRunnable)
    inactivityHandler.postDelayed(lockRunnable, IDLE_LOCK_TIMEOUT)
}


    
    

  private fun hashSecret(secret: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val digest = md.digest(secret.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}



    private fun handleFileAccess(file: File) {

    checkAuth {
        showFileOptionsDialog(file)
    }
}

    private fun showUniversalAuthDialog(onSuccess: () -> Unit, onForgot: () -> Unit) {
    val input = EditText(this).apply { 
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        hint = "Enter Password"
    }
    AlertDialog.Builder(this)
        .setTitle("Session Expired")
        .setMessage("Please verify your identity")
        .setView(input)
        .setCancelable(false)
        .setPositiveButton("Unlock") { _, _ ->
            val inputText = input.text.toString()
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val savedValue = prefs.getString(KEY_VERIFICATION, "") ?: ""
            
            
            val isMatch = if (savedValue.length == 64) {
                hashSecret(inputText) == savedValue
            } else {
                inputText == savedValue
            }

            if (isMatch) {

                if (savedValue.length != 64) {
                    prefs.edit().putString(KEY_VERIFICATION, hashSecret(inputText)).apply()
                }
                onSuccess()
            } else {
                Toast.makeText(this, "Incorrect", Toast.LENGTH_SHORT).show()
            }
        }
        .setNeutralButton("Forgot?", { _, _ -> onForgot() })
        .show()
}

   private fun showRecoveryFlow() {
    val input = EditText(this).apply {
        hint = "Enter 20-character code"
        filters = arrayOf(InputFilter.AllCaps()) 
    }

    AlertDialog.Builder(this)
        .setTitle("Recovery Mode")
        .setMessage("Please enter your emergency recovery code to gain access.")
        .setView(input)
        .setCancelable(false)
        .setPositiveButton("Verify") { _, _ ->
            val inputCode = input.text.toString().trim()
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val savedCode = prefs.getString("recovery_code", "")?.trim() ?: ""


            val isMatch = if (savedCode.length == 64) {
                hashSecret(inputCode) == savedCode
            } else {
                inputCode.equals(savedCode, ignoreCase = true)
            }

            if (isMatch) {

                if (savedCode.length != 64) {
                    prefs.edit().putString("recovery_code", hashSecret(inputCode)).apply()
                }
                
                prefs.edit().putLong("global_auth_timestamp", System.currentTimeMillis()).apply()
                pendingAction?.invoke() 
                pendingAction = null 

                showAuthTypeChoiceDialog() 
                Toast.makeText(this, "Access Granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Incorrect Recovery Code", Toast.LENGTH_LONG).show()
            }
        }
        .setNegativeButton("Cancel") { _, _ -> pendingAction = null }
        .setNeutralButton("Forgot Recovery Code?") { _, _ ->
            showDeveloperLastResortUI()
        }
        .show()
}

  private fun showAuthTypeChoiceDialog() {
    val options = arrayOf("Pattern", "Password")
    
    androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("Select New Security Type")
        .setItems(options) { _, which ->
            when (which) {
                0 -> launchPatternSetup()   
                1 -> showNewPasswordDialog() 
            }
        }
        .setCancelable(false)
        .show()
}

  private fun launchPatternSetup() {
    val intent = Intent(this, CustomPatternActivity::class.java).apply {
        putExtra("IS_SETUP", true) 
    }
    patternUnlockLauncher.launch(intent)
}


private val patternSetupLauncher = registerForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == RESULT_OK) {
        val newPattern = result.data?.getStringExtra("PATTERN_RESULT")
        if (newPattern != null) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().apply {
                putString(KEY_VERIFICATION, newPattern)
                putString("auth_type", "pattern") 
                putLong("global_auth_timestamp", System.currentTimeMillis())
                apply()
            }
            verificationString?.fill('0')


verificationString = newPattern.toCharArray()
            Toast.makeText(this, "Pattern Updated Successfully", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun showNewPasswordDialog() {
    val input = EditText(this).apply { 
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        hint = "Enter New Password"
    }
    
    AlertDialog.Builder(this)
        .setTitle("Reset Password")
        .setMessage("Verification successful. Please set a new password.")
        .setView(input)
        .setCancelable(false) 
        .setPositiveButton("Save") { _, _ ->
            val newPass = input.text.toString()
            if (newPass.length >= 4) {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val hashedPass = hashSecret(newPass) 
                
                prefs.edit().apply {
                    putString(KEY_VERIFICATION, hashedPass)
                    putString("auth_type", "password")
                    putLong("global_auth_timestamp", System.currentTimeMillis()) 
                    apply()
                }
                
                verificationString?.fill('0') 

                verificationString = hashedPass.toCharArray()
                lastAuthTimestamp = System.currentTimeMillis() 
                
                Toast.makeText(this, "Password Updated", Toast.LENGTH_SHORT).show()
                loadFileList()
            } else {
                Toast.makeText(this, "Password too short!", Toast.LENGTH_SHORT).show()
                showNewPasswordDialog()
            }
        }.show()
}

    private fun showDeveloperLastResortUI() {
    val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

    
    AlertDialog.Builder(this)
        .setTitle("Developer Safety Net")
        .setMessage("This is a manual, pre-emptive protocol. If you have not established your identity with the developer prior to losing access, a bypass token will not be issued.\n\n" +
        "To set this up, email the ID below. You will be asked specific security questions to verify your identity. If you request a bypass later, your answers and behavior must match this initial verification. If you cannot be identified with certainty, access will be denied.\n\n" +
        "ID: $deviceId")
        .setPositiveButton("Copy ID") { _, _ ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DeviceID", deviceId))
            Toast.makeText(this, "ID copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("Email Developer") { _, _ ->
    val subject = Uri.encode("Developer Security Net Request")
    val developerName = "Seeker"
    val developerEmail = "mytuta05@tutamail.com"


    val formattedEmail = "$developerName <$developerEmail>"
    val body = "Device ID: $deviceId\n" +
               "Request Type: [REGISTRATION / RECOVERY]\n\n" +
               "I am ________ (setting up / requesting a bypass). " +
               "Please ________ (initiate my security questions / provide my token)."
    

    val uriString = "mailto:$formattedEmail?subject=$subject&body=$body"
    
    val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse(uriString)
    }

    try {
        startActivity(Intent.createChooser(emailIntent, "Send Email..."))
    } catch (e: Exception) {
        Toast.makeText(this, "No email client found", Toast.LENGTH_SHORT).show()
    }
}
        .setNeutralButton("Enter Token") { _, _ -> 
            showTokenInputAndVerify() 
        }
       
        .show()
}

    private fun showTokenInputAndVerify() {
    val input = EditText(this).apply { hint = "Paste Token from Email" }
    AlertDialog.Builder(this)
        .setTitle("Developer Bypass")
        .setView(input)
        .setCancelable(false)
        .setPositiveButton("Verify") { _, _ ->
            val token = input.text.toString().trim()
            

            if (verifyDeveloperOverride(token)) {

                lastAuthTimestamp = System.currentTimeMillis()
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putLong("global_auth_timestamp", lastAuthTimestamp)
                    .apply()


                showAuthTypeChoiceDialog() 
                
                pendingAction?.invoke()
                pendingAction = null
                loadFileList()
            } else {
                Toast.makeText(this, "Invalid Token or Tampered App", Toast.LENGTH_SHORT).show()
            }
        }.show()
}

   private fun getRandomMe(): String {
    val p1 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyWMPPTTkrnXU2N13ncFV6qO5SOaSrZLBWt9YTEhnyQPfO69NPs1HyhFVzJrqMDr0vm94ChcUbe80O6D+"
    val p2 = "zS2FBDyEBU5fOJ9PoZ40f6k4wiglA4dFjFLbwgYGYNKI7iioHXa+rZoifpTFsHfNInEY888Fx3tyKqFM0459gZgUoFB5IfhjZmizay9/g2Vh6ltDaFZ9qTsjSKNbSCtHawozDFzDIayrKf9jwFo8YPbEeq13fpQeX7"
    val p3 = "a16yKLC+ibOFYbgjlYDFYfodVNV+3f9SjA1DpHMJ+fBpdCgYpGwLfnbDUTV8Wc6B8w3u8Is4aeNQDm9lJA4V8cfvpzgIM6mToSrwIDAQAB"

    return p1 + p2 + p3
}

   
   
   private fun isKeyAuthentic(): Boolean {
    return try {
        val md = MessageDigest.getInstance("SHA-256")

        val cleanKey = getRandomMe().replace("\\s".toRegex(), "")
        val digest = md.digest(cleanKey.toByteArray(Charsets.UTF_8))
        val currentKeyHash = Base64.encodeToString(digest, Base64.NO_WRAP)
        
        val HashMe = "zCpCtlKiOM65hjkL3L1tPRAczZXS0K3TP54zhUKlXmg="
        
        val isMatch = currentKeyHash == HashMe
        if (!isMatch) {
            android.util.Log.e("DEBUG_AUTH", "Hash Mismatch! App generated: $currentKeyHash")
        }
        isMatch
    } catch (e: Exception) {
        false
    }
}



    private fun verifyDeveloperOverride(token: String): Boolean {
    if (!isKeyAuthentic()) return false 

    return try {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        

        val cleanKey = getRandomMe().replace("\\s".toRegex(), "")
        val cleanToken = token.replace("\\s".toRegex(), "")

        val publicBytes = Base64.decode(cleanKey, Base64.NO_WRAP)
        val keySpec = X509EncodedKeySpec(publicBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        val pubKey = keyFactory.generatePublic(keySpec)

        val sig = java.security.Signature.getInstance("SHA256withRSA")
        sig.initVerify(pubKey)
        

        sig.update(deviceId.toByteArray(Charsets.UTF_8))
        
        val signatureBytes = Base64.decode(cleanToken, Base64.NO_WRAP)
        

        val result = sig.verify(signatureBytes)
        

        result 
        
    } catch (e: Exception) { 
        android.util.Log.e("DevOverride", "Bypass failed: ${e.message}")
        false 
    }
}





    private fun getTargetFile(fileName: String, fileSize: Long, subPath: String): File {
    val limit = 50 * 1024 * 1024 
    

    val baseRoot = if (fileSize > limit) encryptedDir else internalEncDir
    

    val targetFolder = File(baseRoot, subPath)
    

    if (!targetFolder.exists()) targetFolder.mkdirs()
    
    return File(targetFolder, fileName)
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


    private fun hideLoadingDialog() { progressDialog?.dismiss() }

    private fun filterFiles(query: String) {
        val filtered = if (query.isEmpty()) allFilesMasterList 
                       else allFilesMasterList.filter { it.name.contains(query, ignoreCase = true) }
        fileAdapter.updateData(filtered)
        countTextView.text = if (query.isEmpty()) "${allFilesMasterList.size} Items" 
                             else "${filtered.size} found"
    }

    private fun queryFileName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) return cursor.getString(index)
            }
        }
        return null
    }

    private fun deleteExternalFile(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            androidx.documentfile.provider.DocumentFile.fromSingleUri(this, uri)?.delete()
        } catch (e: Exception) {  }
    }
    
    private fun showNewFolderDialog() {
    val input = EditText(this).apply { hint = "Folder Name" }
    AlertDialog.Builder(this)
        .setTitle("New Folder")
        .setView(input)
        .setPositiveButton("Create") { _, _ ->
            val folderName = input.text.toString().trim()
            if (folderName.isNotEmpty()) {

                val internalBase = if (isEncryptedFolder) File(filesDir, "Encrypted") else File(filesDir, "Decrypted")
                val internalFolder = File(internalBase, folderName)
                internalFolder.mkdirs()


                val externalBase = if (isEncryptedFolder) encryptedDir else decryptedDir
                val externalFolder = File(externalBase, folderName)
                externalFolder.mkdirs()

                loadFileList() 
            }
        }.show()
}
    
    private fun showFileOptionsDialog(file: File) {

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

    
    private fun confirmDeletion(file: File) {
    AlertDialog.Builder(this)
        .setTitle("Delete ${file.name}?")
        .setMessage("This action cannot be undone. The file will be permanently removed.")
        .setPositiveButton("Delete") { _, _ ->
            if (secureDelete(file)) {
                loadFileList()
                Toast.makeText(this, "File deleted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to delete file", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
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
    
   private fun showMoveDialog(file: File) {

    val internalBase = if (isEncryptedFolder) File(filesDir, "Encrypted") else File(filesDir, "Decrypted")
    val externalBase = if (isEncryptedFolder) encryptedDir else decryptedDir


    val internalSubs = internalBase.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
    val externalSubs = externalBase.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()


    val allFolderNames = (internalSubs.map { it.name } + externalSubs.map { it.name }).distinct()

    val options = mutableListOf<String>()
    

    val isAtRoot = (currentDir.absolutePath == internalBase.absolutePath || currentDir.absolutePath == externalBase.absolutePath)
    if (!isAtRoot) options.add(".. (Back to Root)")
    
    options.addAll(allFolderNames)

    if (options.isEmpty()) {
        Toast.makeText(this, "No folders to move to", Toast.LENGTH_SHORT).show()
        return
    }

    AlertDialog.Builder(this)
        .setTitle("Move ${file.name} to:")
        .setItems(options.toTypedArray()) { _, which ->
            val selection = options[which]
            

            val fileSize = file.length()
            val limit = 50 * 1024 * 1024 
            val targetBase = if (fileSize > limit) externalBase else internalBase
            
            val targetDir = if (selection == ".. (Back to Root)") {
                targetBase
            } else {
                File(targetBase, selection)
            }

            if (!targetDir.exists()) targetDir.mkdirs()

            performMoveExecution(file, targetDir)
        }.show()
}



  private fun performMoveExecution(file: File, targetDir: File) {
    val destFile = File(targetDir, file.name)
    
    lifecycleScope.launch(Dispatchers.IO) {
        val success = try {
            if (file.renameTo(destFile)) {
                true
            } else {

                file.inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                secureDelete(file)
                true
            }
        } catch (e: Exception) {
            if (destFile.exists()) secureDelete(destFile)
            false
        }

        withContext(Dispatchers.Main) {
            if (success) {
                loadFileList() 
                Toast.makeText(this@FolderFilesActivity, "Moved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@FolderFilesActivity, "Move failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}


  private fun promptDecryptFile(file: File) {
    
    checkAuth {

        performDecryption(file)
    }
}


private fun performDecryption(file: File) {
    showLoadingDialog("Moving to Decrypted...")
    lifecycleScope.launch {
        withContext(Dispatchers.IO) {
            val decryptedName = file.name.removeSuffix(".enc")
            
            val fileSize = file.length() 
            val limit = 50 * 1024 * 1024
            val baseDir = if (fileSize > limit) decryptedDir else File(filesDir, "Decrypted")
            
            if (!baseDir.exists()) baseDir.mkdirs()
            val targetFile = File(baseDir, decryptedName)
            
            decryptFile(FileInputStream(file), targetFile)
            secureDelete(file)
        }
        loadFileList()
        hideLoadingDialog()
    }
}

  private fun promptEncryptFile(file: File) {
    val newName = if (file.name.endsWith(".enc")) file.name else "${file.name}.enc"
    showLoadingDialog("Encrypting...")
    
    lifecycleScope.launch {
        withContext(Dispatchers.IO) {
            // 1. Calculate the relative path of where you are right now
            val relativePath = if (currentDir.absolutePath.startsWith(internalEncDir.absolutePath)) {
                currentDir.absolutePath.removePrefix(internalEncDir.absolutePath)
            } else if (currentDir.absolutePath.startsWith(encryptedDir.absolutePath)) {
                currentDir.absolutePath.removePrefix(encryptedDir.absolutePath)
            } else {
                // If it's coming from outside the vault (Decrypted folder), 
                // we treat it as root or maintain its current subfolder structure
                "" 
            }

            // 2. Pass the relativePath as the third argument
            val targetFile = getTargetFile(newName, file.length(), relativePath)
            
            encryptFile(FileInputStream(file), targetFile)
            secureDelete(file)
        }
        loadFileList()
        hideLoadingDialog()
        Toast.makeText(this@FolderFilesActivity, "Encrypted successfully", Toast.LENGTH_SHORT).show()
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
    
    
  private fun checkAuth(action: () -> Unit) {
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    val lastAuth = prefs.getLong("global_auth_timestamp", 0L)
    val currentTime = System.currentTimeMillis()


    if (currentTime - lastAuth < SESSION_TIMEOUT) {
        action()
        return
    }


    val type = prefs.getString("auth_type", "password")
    
    when (type) {
        "biometric" -> {
            showSystemAuthForVerification(action)
        }
        "pattern" -> {
    pendingAction = action
    val intent = Intent(this, CustomPatternActivity::class.java)
    intent.putExtra("IS_SETUP", false)
    patternUnlockLauncher.launch(intent)
}
        else -> {

            showUniversalAuthDialog(
                onSuccess = { 

                    prefs.edit().putLong("global_auth_timestamp", System.currentTimeMillis()).apply()
                    action() 
                },
                onForgot = { showRecoveryFlow() }
            )
        }
    }
}

private fun showSystemAuthForVerification(onSuccess: () -> Unit) {
    val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
    val biometricPrompt = androidx.biometric.BiometricPrompt(this, executor, 
        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putLong("global_auth_timestamp", System.currentTimeMillis())
                    .apply()
                
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)

                Toast.makeText(this@FolderFilesActivity, "Auth Error: $errString", Toast.LENGTH_SHORT).show()
            }
        })

    val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
        .setTitle("Fingerprint Required")
        .setSubtitle("Touch the sensor to unlock")

        .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText("Use Password") 
        .build()

    biometricPrompt.authenticate(promptInfo)
}
 

}


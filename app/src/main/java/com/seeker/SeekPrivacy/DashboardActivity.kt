package com.seeker.seekprivacy

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import java.security.MessageDigest
import android.util.Base64
import android.provider.Settings

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt

import com.seeker.seekprivacy.CustomPatternActivity

import android.widget.Button

import java.security.*
import java.security.spec.X509EncodedKeySpec

import java.security.KeyFactory

import android.text.InputFilter



class DashboardActivity : AppCompatActivity() {

    private lateinit var encryptedFolderLayout: MaterialCardView
    private lateinit var decryptedFolderLayout: MaterialCardView
    private lateinit var sharedPreferences: SharedPreferences

    private val PREFS_NAME = "SeekPrivacyPrefs"
    private val KEY_VERIFICATION = "verificationKey"
    private val KEY_IS_MIGRATED = "is_migrated_to_v2"
    
    private var verificationString: CharArray? = null
    private var selectedFolderEncrypted = true
    private var permissionsChecked = false
    private var isIntegrityValid = true
    
    private var pendingAction: (() -> Unit)? = null


    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                permissionsChecked = true
            } else {
                Toast.makeText(this, "Storage permissions are required", Toast.LENGTH_LONG).show()
            }
        }
        
    private val patternSetupLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == RESULT_OK) {
        val finalPattern = result.data?.getStringExtra("PATTERN_RESULT")

        saveAuthAndShowRecovery("pattern", finalPattern)
    }
}


private val patternLoginLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    when (result.resultCode) {
        RESULT_OK -> {
            
            sharedPreferences.edit()
                .putLong("global_auth_timestamp", System.currentTimeMillis())
                .apply()
            

            checkPermissionsAtStartup()
        }
        99 -> showRecoveryFlow() 
        else -> finish() 
    }
}


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (!verifyAppIntegrity()) {

        Toast.makeText(this, "Security Violation: App terminated", Toast.LENGTH_LONG).show()
        finish() 
        return 
    }
    
    
        setContentView(R.layout.activity_dashboard)

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        verificationString = sharedPreferences.getString(KEY_VERIFICATION, null)?.toCharArray()

       

        encryptedFolderLayout = findViewById(R.id.encryptedFolderLayout)
        decryptedFolderLayout = findViewById(R.id.decryptedFolderLayout)
        
        

val btnDeveloperSetup = findViewById<Button>(R.id.btnDeveloperSetup)

btnDeveloperSetup.setOnClickListener {
    showDeveloperLastResortUI()
}

        setupCreatorLink()


        encryptedFolderLayout.setOnClickListener {
            selectedFolderEncrypted = true
            handleFolderClick()
        }

        decryptedFolderLayout.setOnClickListener {
            selectedFolderEncrypted = false
            handleFolderClick()
        }
        
        


        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val hasLegacyPassword = sharedPreferences.getString(KEY_VERIFICATION, null) != null

if (hasLegacyPassword && !sharedPreferences.contains("setup_step")) {

    sharedPreferences.edit().putInt("setup_step", 4).putBoolean(KEY_IS_MIGRATED, true).apply()
}



    val currentStep = sharedPreferences.getInt("setup_step", 0)

    if (currentStep >= 4) {
        
        promptUserAuthentication()
    } else {
        
        checkSetupProgress()
    }
    
    
    }
    
    
    private fun promptUserAuthentication() {
    val type = sharedPreferences.getString("auth_type", "password") ?: "password"
    val savedSecret = sharedPreferences.getString(KEY_VERIFICATION, null)

    when (type) {
        "biometric" -> showSystemAuthForLogin()
        "password" -> showPasswordLogin(savedSecret)
        "pattern" -> {
            val intent = Intent(this, CustomPatternActivity::class.java)
            intent.putExtra("IS_SETUP", false) 
            patternLoginLauncher.launch(intent)
        }
    }
}


  private fun showPasswordLogin(savedSecret: String?) {
    val input = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        hint = "Enter Password"
    }

    AlertDialog.Builder(this)
        .setTitle("Unlock Required")
        .setView(input)
        .setCancelable(false)
        .setPositiveButton("Unlock") { _, _ ->
            val inputText = input.text.toString()
            
            val isMatch = if (savedSecret?.length == 64) {
                hashSecret(inputText) == savedSecret
            } else {
                inputText == savedSecret
            }

            if (isMatch) {
                checkPermissionsAtStartup()
            } else {
                Toast.makeText(this, "Incorrect Password", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        .setNeutralButton("Forgot?") { _, _ ->
            showRecoveryFlow() 
        }
        .setNegativeButton("Exit") { _, _ -> finish() }
        .show()
}

    
    
    
    private fun showSystemAuthForLogin() {
    val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
    val biometricPrompt = androidx.biometric.BiometricPrompt(this, executor,
        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                checkPermissionsAtStartup() 
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Toast.makeText(this@DashboardActivity, "Authentication failed: $errString", Toast.LENGTH_SHORT).show()
                finish() 
            }
        })

    val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock App")
        .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText("Exit")
        .build()

    biometricPrompt.authenticate(promptInfo)
}



     
  

    

    private fun showSetupWizard() {
    val options = arrayOf("Password", "Pattern", "Biometric")
    var selected = 0
    
    val builder = AlertDialog.Builder(this)
    builder.setTitle("Upgrade Security")
    builder.setCancelable(false)
    
    builder.setSingleChoiceItems(options, 0) { _, which ->
        selected = which
    }
    
    builder.setPositiveButton("Select") { _, _ ->
        val chosenType = when (selected) {
            0 -> "password"
            1 -> "pattern"
            else -> "biometric"
        }
        

        sharedPreferences.edit().apply {
            putString("auth_type", chosenType)
            putInt("setup_step", 1) 
            apply()
        }
        

        handleSecretSetup(chosenType)
    }
    builder.show()
}



    private fun handleSecretSetup(type: String) {
    when (type) {
        "password" -> showPasswordSetup(type)
        "biometric" -> {
            showSystemAuthForSetup {
                saveAuthAndShowRecovery(type, null)
            }
        }
        "pattern" -> {
    val intent = Intent(this, CustomPatternActivity::class.java)
    intent.putExtra("IS_SETUP", true)
    patternSetupLauncher.launch(intent) 
}
    }
}



private fun showSystemAuthForSetup(onSuccess: () -> Unit) {
    val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
    val biometricPrompt = androidx.biometric.BiometricPrompt(this, executor, 
        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess() 
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Toast.makeText(this@DashboardActivity, "Setup Error: $errString", Toast.LENGTH_SHORT).show()
            }
        })

    val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
        .setTitle("Register Biometric")
        .setSubtitle("Authenticate to enable biometric locking")

        .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText("Cancel")
        .build()

    biometricPrompt.authenticate(promptInfo)
}




    private fun showPasswordSetup(type: String) {
        val input = EditText(this).apply {
            hint = "Enter New Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("Set Password")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val pass = input.text.toString()
                if (pass.length < 4) {
                    Toast.makeText(this, "Password too short!", Toast.LENGTH_SHORT).show()
                    showPasswordSetup(type)
                } else {
                    saveAuthAndShowRecovery(type, pass)
                }
            }
            .show()
    }

    private fun saveAuthAndShowRecovery(type: String, secret: String?, passedRecoveryCode: String? = null) {

    val recoveryCode = (passedRecoveryCode ?: run {
        val allowedChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val secureRandom = java.security.SecureRandom()
        (1..20).map { allowedChars[secureRandom.nextInt(allowedChars.length)] }.joinToString("")
    }).trim() 


    sharedPreferences.edit().apply {
        putString("auth_type", type)
        putString("recovery_code", hashSecret(recoveryCode))
        if (secret != null) putString(KEY_VERIFICATION, hashSecret(secret))
        putBoolean(KEY_IS_MIGRATED, true)
        putInt("setup_step", 2) 
        apply()
    }

    verificationString = secret?.toCharArray() ?: verificationString


    AlertDialog.Builder(this)
        .setTitle("Save Your Recovery Code")
        .setMessage("If you forget your $type, you will need this code:\n\n$recoveryCode")
        .setCancelable(false)
        .setNeutralButton("Copy to Clipboard") { _, _ ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            clipboard.setPrimaryClip(ClipData.newPlainText("Recovery Code", recoveryCode.trim()))
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
            
            saveAuthAndShowRecovery(type, secret, recoveryCode) 
        }
        .setPositiveButton("I have saved it") { _, _ ->
            sharedPreferences.edit().putInt("setup_step", 3).apply()
            showDeveloperLastResortUI2()
        }
        .show()
}

  
  private fun hashSecret(secret: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val digest = md.digest(secret.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

   private fun checkSetupStatus() {
    val step = sharedPreferences.getInt("setup_step", 0)
    val authType = sharedPreferences.getString("auth_type", "password") ?: "password"
    val secret = sharedPreferences.getString(KEY_VERIFICATION, null)
    val recovery = sharedPreferences.getString("recovery_code", null)

    when (step) {
        0 -> showSetupWizard() 
        1 -> handleSecretSetup(authType) 
        2 -> saveAuthAndShowRecovery(authType, secret, recovery) 
        3 -> showDeveloperLastResortUI() 

    }
}



  private fun showDeveloperLastResortUI() {
    val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    
    AlertDialog.Builder(this)
        .setTitle("Developer Safety Net")
        .setMessage("This is a manual, pre-emptive protocol. If you have not established your identity with the developer prior to losing access, a bypass token will not be issued.\n\n" +
        "To set this up, email the ID below. You will be asked specific security questions to verify your identity. If you request a bypass later, your answers and behavior must match this initial verification. If you cannot be identified with certainty, access will be denied.\n\n" +
        "ID: $deviceId")
        .setCancelable(false)
        .setPositiveButton("Copy ID & Finish") { _, _ ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            sharedPreferences.edit().putInt("setup_step", 4).apply()
            clipboard.setPrimaryClip(ClipData.newPlainText("DeviceID", deviceId))
            Toast.makeText(this, "Setup Complete", Toast.LENGTH_SHORT).show()
            checkPermissionsAtStartup()
        }
        .setNegativeButton("Email Now") { _, _ ->
            sendBypassEmail(deviceId)
            checkPermissionsAtStartup()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

    private fun handleFolderClick() {
        if (!permissionsChecked && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            showPermissionExplanationDialog()
            return
        }
        openFolder(selectedFolderEncrypted)
    }

    private fun checkPermissionsAtStartup() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            if (!areReadWritePermissionsGranted()) {
                showPermissionExplanationDialog()
            } else {
                permissionsChecked = true
            }
        } else {
            permissionsChecked = true
        }
    }
    
    private fun checkSetupProgress() {
    val step = sharedPreferences.getInt("setup_step", 0)
    

    val type = sharedPreferences.getString("auth_type", "password") ?: "password"
    val secret = sharedPreferences.getString(KEY_VERIFICATION, null)
    val code = sharedPreferences.getString("recovery_code", null)

    when (step) {
        0 -> showSetupWizard()
        1 -> handleSecretSetup(type)
        2 -> saveAuthAndShowRecovery(type, secret, code)
        3 -> showDeveloperLastResortUI()
        4 -> { checkPermissionsAtStartup() }
    }
}

    private fun areReadWritePermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionExplanationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Storage Permissions")
            .setMessage("SeekPrivacy needs Storage permissions to manage your files safely.")
            .setCancelable(false)
            .setPositiveButton("Grant") { _, _ -> requestReadWritePermissions() }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    private fun requestReadWritePermissions() {
        permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }

    private fun openFolder(encrypted: Boolean) {
        val intent = Intent(this, FolderFilesActivity::class.java).apply {
            putExtra("isEncryptedFolder", encrypted)
            putExtra("verificationString", verificationString)
            putExtra("integrity_valid", isIntegrityValid)
        }
        startActivity(intent)
    }

    
    
    private fun setupCreatorLink() {
    val contactUs = findViewById<TextView>(R.id.creatorInfo)
    val fullText = "Activist Grade Security System: By Seeker - Founder @ SeeknWander"
    val spannable = SpannableString(fullText)
    val target = "Seeker - Founder @ SeeknWander"
    val start = fullText.indexOf(target)
    val end = start + target.length

    if (start != -1) {
        spannable.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(Color.parseColor("#1E88E5")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    
    contactUs.text = spannable
    
    contactUs.setOnClickListener {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Support & Collaboration")
            .setMessage("Instead of funding, support me by creating a Teleport account/profile (only if it aligns with your privacy and you operate as a business entity). If the former is not possible, then maintain a line via email; in the privacy community, connection and coordination are the only real leverage we have.")
            

            .setPositiveButton("Seeknwander") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://seeknwander.com")))
            }


            .setNeutralButton("Teleport (Who Am I?)") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://seeknwander.com/teleport/profile/seeker?i=seeker")))
            }
            

            .setNegativeButton("Email") { _, _ ->
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mytuta05@tutamail.com")
                }
                startActivity(intent)
            }
            .show()
    }
}
    
    private fun verifyAppIntegrity(): Boolean {
    return try {

        val isDebuggable = (0 != (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE))
        if (isDebuggable) return false


        val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }


        val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo.signingInfo.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }

        val md = MessageDigest.getInstance("SHA-256")
        md.update(signatures[0].toByteArray())
        val currentSignatureHash = Base64.encodeToString(md.digest(), Base64.NO_WRAP)


        val expectedSignatureHash = getSignatureMask()


        currentSignatureHash == expectedSignatureHash

    } catch (e: Exception) {
        false
    }
}

  private fun getSignatureMask(): String {
    val encoded = intArrayOf(
    0x2f, 0x40, 0x41, 0x38, 0x1c, 0x54, 0x30, 0x27,
    0x1f, 0x18, 0x6f, 0x3f, 0x7a, 0x23, 0x0f, 0x1f,
    0x2f, 0x34, 0x5a, 0x12, 0x20, 0x1d, 0x40, 0x01,
    0x58, 0x43, 0x26, 0x2b, 0x09, 0x0c, 0x10, 0x36,
    0x10, 0x05, 0x10, 0x56, 0x35, 0x0d, 0x36, 0x14,
    0x09, 0x12, 0x31, 0x56,
)

    val rank = "dkwhudgSHJDG7sbwigbgx"
    
    return encoded.mapIndexed { index, i ->
        (i xor rank[index % rank.length].code).toChar()
    }.joinToString("")
}

   private fun sendBypassEmail(deviceId: String) {
    val email = "mytuta05@tutamail.com"
    val subject = android.net.Uri.encode("Developer Security Net Request")
    val body = "Device ID: $deviceId\n" +
               "Request Type: [REGISTRATION / RECOVERY]\n\n" +
               "I am ________ (setting up / requesting a bypass). " +
               "Please ________ (initiate my security questions / provide my token)."
    

    val uriString = "mailto:$email?subject=$subject&body=$body"
    
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = android.net.Uri.parse(uriString)
    }

    try {
        startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(this, "No email app found", android.widget.Toast.LENGTH_SHORT).show()
    }
}


  

    override fun onResume() {
        super.onResume()
        if (!permissionsChecked && sharedPreferences.getBoolean(KEY_IS_MIGRATED, false)) {
            checkPermissionsAtStartup()
        }
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
                finish()
            }
        }
        .setNegativeButton("Cancel") 
        { _, _ -> pendingAction = null 
          finish()
        }
        .setNeutralButton("Forgot Recovery Code?") { _, _ ->
            showDeveloperLastResortUI2()
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
    patternSetupLauncher.launch(intent)
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
                
                
                Toast.makeText(this, "Password Updated", Toast.LENGTH_SHORT).show()
                
            } else {
                Toast.makeText(this, "Password too short!", Toast.LENGTH_SHORT).show()
                showNewPasswordDialog()
            }
        }.show()
}

    private fun showDeveloperLastResortUI2() {
    val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

    
    AlertDialog.Builder(this)
        .setTitle("Developer Safety Net")
        .setMessage("This is a manual, pre-emptive protocol. If you have not established your identity with the developer prior to losing access, a bypass token will not be issued.\n\n" +
        "To set this up, email the ID below. You will be asked specific security questions to verify your identity. If you request a bypass later, your answers and behavior must match this initial verification. If you cannot be identified with certainty, access will be denied.\n\n")
        .setPositiveButton("Copy ID") { _, _ ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DeviceID", deviceId))
            Toast.makeText(this, "ID copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("Email Developer") { _, _ ->
    val subject = Uri.encode("Security Bypass Request")
    val body = "Device ID: $deviceId\n" +
               "Request Type: [REGISTRATION / RECOVERY]\n\n" +
               "I am ________ (setting up / requesting a bypass). " +
               "Please ________ (initiate my security questions / provide my token)."
    

    val uriString = "mailto:mytuta05@tutamail.com?subject=$subject&body=$body"
    
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
        .setNegativeButton("Cancel", null)
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
            

            if (SM(token)) {
                


                showAuthTypeChoiceDialog() 
                
                pendingAction?.invoke()
                pendingAction = null
                
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

   
   
   private fun isMe(): Boolean {
    return try {
        val md = MessageDigest.getInstance("SHA-256")

        val cleanKey = getRandomMe().replace("\\s".toRegex(), "")
        val digest = md.digest(cleanKey.toByteArray(Charsets.UTF_8))
        val currentKeyHash = Base64.encodeToString(digest, Base64.NO_WRAP)
        
        val HashMe = "zCpCtlKiOM65hjkL3L1tPRAczZXS0K3TP54zhUKlXmg="
        
        val isMatch = currentKeyHash == HashMe
        
        isMatch
    } catch (e: Exception) {
        false
    }
}
    private fun SM(token: String): Boolean {
    if (!isMe()) return false 

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
}

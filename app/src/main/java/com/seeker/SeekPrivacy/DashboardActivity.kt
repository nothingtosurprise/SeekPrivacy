package com.seeker.seekprivacy

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView

class DashboardActivity : AppCompatActivity() {

    private lateinit var encryptedFolderLayout: MaterialCardView
    private lateinit var decryptedFolderLayout: MaterialCardView
    private lateinit var sharedPreferences: SharedPreferences

    private val PREFS_NAME = "EncryptPrefs"
    private val KEY_VERIFICATION = "verificationKey"
    private val KEY_FIRST_LAUNCH = "firstLaunchDone"
    private var verificationString: String? = null

    private var selectedFolderEncrypted = true

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                openFolder(selectedFolderEncrypted)
            } else {
                Toast.makeText(this, "Storage permissions are required", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        verificationString = sharedPreferences.getString(KEY_VERIFICATION, null)

        encryptedFolderLayout = findViewById(R.id.encryptedFolderLayout)
        decryptedFolderLayout = findViewById(R.id.decryptedFolderLayout)

        encryptedFolderLayout.setOnClickListener {
            selectedFolderEncrypted = true
            checkPermissionsAndOpenFolder()
        }

        decryptedFolderLayout.setOnClickListener {
            selectedFolderEncrypted = false
            checkPermissionsAndOpenFolder()
        }

        if (!sharedPreferences.getBoolean(KEY_FIRST_LAUNCH, false) || verificationString.isNullOrEmpty()) {
            promptVerificationString()
        }
    }

    private fun checkPermissionsAndOpenFolder() {
        if (arePermissionsGranted()) {
            openFolder(selectedFolderEncrypted)
        } else {
            requestPermissions()
        }
    }

    private fun arePermissionsGranted(): Boolean {
        val readPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
        val writePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val managePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Settings.System.canWrite(this)
        } else true

        return readPermission == PackageManager.PERMISSION_GRANTED &&
                writePermission == PackageManager.PERMISSION_GRANTED &&
                managePermission
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        permissionLauncher.launch(permissions)

        
    }

    private fun openFolder(encrypted: Boolean) {
        val intent = Intent(this, FolderFilesActivity::class.java).apply {
            putExtra("isEncryptedFolder", encrypted)
            putExtra("verificationString", verificationString)
        }
        startActivity(intent)
    }

    private fun promptVerificationString() {
        val input = EditText(this).apply {
            hint = "Enter secret verification string"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("Set Verification String")
            .setMessage("This string will be used for decrypting files.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save") { dialog, _ ->
                val entered = input.text.toString()
                if (entered.isBlank()) {
                    Toast.makeText(this, "Verification string cannot be empty.", Toast.LENGTH_SHORT).show()
                    promptVerificationString()
                } else {
                    sharedPreferences.edit()
                        .putString(KEY_VERIFICATION, entered)
                        .putBoolean(KEY_FIRST_LAUNCH, true)
                        .apply()
                    verificationString = entered
                    dialog.dismiss()
                }
            }
            .show()
    }
}


package com.seeker.seekprivacy

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CustomPatternActivity : AppCompatActivity() {

    private lateinit var patternView: PatternView
    private var tempPattern = ""
    private var isSetupMode = true


    private val PREFS_NAME = "SeekPrivacyPrefs"
    private val KEY_VERIFICATION = "verificationKey"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_pattern)

        patternView = findViewById<PatternView>(R.id.patternView)
        val hintText = findViewById<TextView>(R.id.patternHint)
        val btnConfirm = findViewById<Button>(R.id.btnConfirm)
        val btnForgot = findViewById<TextView>(R.id.btnForgotPattern)

        isSetupMode = intent.getBooleanExtra("IS_SETUP", true)
        
        if (isSetupMode) {
            hintText.text = "Setup Your Pattern"
            btnForgot.visibility = View.GONE
        } else {
            hintText.text = "Draw Pattern to Unlock"
            btnForgot.visibility = View.VISIBLE
        }

        patternView.onPatternListener = { pattern: String ->
            if (pattern.length < 4) {
                Toast.makeText(this, "Connect at least 4 dots", Toast.LENGTH_SHORT).show()
                patternView.reset()
            } else {
                tempPattern = pattern
                if (isSetupMode) {
                    btnConfirm.visibility = View.VISIBLE
                    hintText.text = "Pattern Recorded"
                } else {
                    verifyAndFinish(pattern)
                }
            }
        }

        btnConfirm.setOnClickListener {
            val resultIntent = Intent()
            resultIntent.putExtra("PATTERN_RESULT", tempPattern)
            setResult(RESULT_OK, resultIntent)
            finish()
        }


        btnForgot.setOnClickListener {
            setResult(99) 
            finish()
        }
    }

    private fun verifyAndFinish(enteredPattern: String) {
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    val savedHash = prefs.getString(KEY_VERIFICATION, "")
    

    val enteredHash = hashSecret(enteredPattern)

    if (enteredHash == savedHash) {
        prefs.edit().putLong("global_auth_timestamp", System.currentTimeMillis()).apply()
        setResult(RESULT_OK)
        finish()
    } else {
        Toast.makeText(this, "Wrong Pattern!", Toast.LENGTH_SHORT).show()
        patternView.reset()
    }
}


  private fun hashSecret(secret: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val digest = md.digest(secret.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
}

package com.seeker.seekprivacy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class SplashActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 100
    private val SPLASH_DURATION_MS = 3000L
    private val handler = Handler(Looper.getMainLooper())

    

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        
        setContentView(R.layout.activity_splash)

        
        
        handler.postDelayed({
    startActivity(Intent(this, DashboardActivity::class.java))
    finish()
}, SPLASH_DURATION_MS)

    }

  
}


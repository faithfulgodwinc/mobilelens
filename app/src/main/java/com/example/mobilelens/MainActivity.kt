package com.example.mobilelens

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val screenCaptureIntentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data != null) {
                // Start the foreground service with the projection intent
                val serviceIntent = Intent(this, LensService::class.java).apply {
                    putExtra(LensService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(LensService.EXTRA_RESULT_DATA, data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                // Finish the activity so it runs entirely in the background overlay
                finish()
            }
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val mainContent = findViewById<android.view.View>(R.id.main_content)
        mainContent.alpha = 0f
        mainContent.animate().alpha(1f).setDuration(800).start()

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_launch_lens).setOnClickListener {
            checkPermissionsAndStart()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Quench the magnifier if the user presses HOME
        quenchMagnifier()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        // Quench the magnifier if the user navigates BACK
        quenchMagnifier()
    }

    private fun quenchMagnifier() {
        val serviceIntent = Intent(this, LensService::class.java)
        stopService(serviceIntent)
    }

    private fun checkPermissionsAndStart() {
        // 1. Check Overlay Permission
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            Toast.makeText(this, "Please grant 'Display over other apps' permission", Toast.LENGTH_LONG).show()
            startActivity(intent)
            return
        }

        // 2. Check Notifications Permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
                return
            }
        }

        // 3. Request Screen Capture Intent (MediaProjection)
        startScreenCapture()
    }

    private fun startScreenCapture() {
        screenCaptureIntentLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }
}

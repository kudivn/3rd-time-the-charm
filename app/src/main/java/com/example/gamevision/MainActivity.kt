package com.example.gamevision

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val svc = Intent(this, CaptureService::class.java)
                svc.putExtra("resultCode", result.resultCode)
                svc.putExtra("resultData", result.data)
                startForegroundService(svc)
                finish()
            } else {
                // user denied
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startBtn = Button(this).apply {
            text = "Start GameVision Capture"
            setOnClickListener { startFlow() }
        }
        setContentView(startBtn)

        // request overlay permission if needed
        if (!Settings.canDrawOverlays(this)) {
            val uri = Uri.parse("package:$packageName")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri))
        }
    }

    private fun startFlow() {
        val mpMgr = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val intent = mpMgr.createScreenCaptureIntent()
        projectionLauncher.launch(intent)
    }
}

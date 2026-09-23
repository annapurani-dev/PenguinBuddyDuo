package com.example.penguinbuddy

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
        }
        
        val btnBlue = Button(this).apply {
            text = "Launch as BLUE Penguin"
            setOnClickListener { launchService("blue") }
        }
        
        val btnRed = Button(this).apply {
            text = "Launch as RED Penguin"
            setOnClickListener { launchService("red") }
        }
        
        val btnStop = Button(this).apply {
            text = "Stop / Hide Buddy"
            setOnClickListener { 
                stopService(Intent(this@MainActivity, FloatingService::class.java))
                Toast.makeText(this@MainActivity, "Buddy hidden!", Toast.LENGTH_SHORT).show()
            }
        }
        
        layout.addView(btnBlue)
        layout.addView(btnRed)
        layout.addView(btnStop)
        setContentView(layout)
    }
    
    private fun launchService(role: String) {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 1)
        } else {
            val serviceIntent = Intent(this, FloatingService::class.java).apply {
                putExtra("ROLE", role)
            }
            startService(serviceIntent)
            Toast.makeText(this, "Launched as $role!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

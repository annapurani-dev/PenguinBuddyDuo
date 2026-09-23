package com.example.penguinbuddy

import android.animation.ObjectAnimator
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class FloatingService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var container: FrameLayout
    private lateinit var spriteView: SpriteView
    private lateinit var notifView: TextView
    private lateinit var visitorSprite: SpriteView
    private lateinit var params: WindowManager.LayoutParams
    
    private var role = "blue"
    private var activeState = "none"
    private val client = OkHttpClient()
    private val serverUrl = "https://kvdb.io/44FNGEYy2QA8MqFD7Y6PAc/state"
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    
    private var screenWidth = 0
    private var isAnimating = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        role = intent?.getStringExtra("ROLE") ?: "blue"
        setupUI()
        startPolling()
        return START_STICKY
    }

    private fun setupUI() {
        if (::windowManager.isInitialized) return
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels

        container = FrameLayout(this)
        
        spriteView = SpriteView(this)
        notifView = TextView(this).apply {
            text = "❗️"
            textSize = 30f
            setTextColor(Color.RED)
            visibility = View.GONE
            translationY = -80f
            translationX = 100f
        }
        
        visitorSprite = SpriteView(this).apply {
            visibility = View.GONE
        }
        
        container.addView(spriteView)
        container.addView(notifView)
        container.addView(visitorSprite)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = if (role == "blue") -150 else screenWidth - 220
        params.y = 500

        // Add the tilt
        container.rotation = if (role == "blue") 10f else -10f

        windowManager.addView(container, params)
        applyIdleState()

        var lastClickTime = 0L
        spriteView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                if (isAnimating) return false
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)
                        if (diffX < 50 && diffY < 50) { // Increased touch slop
                            val clickTime = System.currentTimeMillis()
                            if (clickTime - lastClickTime < 500) { // Increased double tap timeout
                                onDoubleTap()
                                lastClickTime = 0L // Reset to prevent triple tap counting as two double taps
                            } else {
                                onSingleTap()
                                lastClickTime = clickTime
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(container, params)
                        return true
                    }
                }
                return false
            }
        })
        
        visitorSprite.setOnClickListener {
            onVisitorTap()
        }
    }
    
    private fun applyIdleState() {
        val res = if (role == "blue") R.drawable.blue_idle else R.drawable.red_idle
        val faceLeft = role == "red"
        spriteView.setSprite(res, false, scale = 2.5f)
        spriteView.setFacingLeft(faceLeft)
    }

    private fun startPolling() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val request = Request.Builder().url(serverUrl).build()
                    val response = client.newCall(request).execute()
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val newState = json.optString("activeState", "none")
                    
                    if (newState != activeState) {
                        withContext(Dispatchers.Main) {
                            handleStateChange(newState)
                        }
                    }
                } catch (e: Exception) { }
                delay(1000)
            }
        }
    }
    
    private fun pushState(state: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().put("activeState", state).toString()
                val body = json.toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(serverUrl).post(body).build()
                client.newCall(req).execute()
            } catch (e: Exception) { }
        }
    }

    private fun onDoubleTap() {
        if (activeState == "none") {
            pushState("waiting_$role")
        }
    }

    private fun onSingleTap() {
        val other = if (role == "blue") "red" else "blue"
        if (activeState == "waiting_$other") {
            notifView.visibility = View.GONE
            pushState("visiting_$other")
        }
    }
    
    private fun onVisitorTap() {
        val other = if (role == "blue") "red" else "blue"
        if (activeState == "visiting_$role") { // We are the host hugging
            pushState("none")
        }
    }

    private fun handleStateChange(newState: String) {
        val oldState = activeState
        activeState = newState
        val other = if (role == "blue") "red" else "blue"

        if (newState == "waiting_$role") {
            // We requested a hug
            val res = if (role == "blue") R.drawable.blue_sad else R.drawable.red_sad
            spriteView.setSprite(res, false, scale = 2.5f)
        } else if (newState == "waiting_$other") {
            // Other requested a hug -> show notification
            notifView.visibility = View.VISIBLE
        } else if (newState == "visiting_$role") {
            // Someone is visiting US (we are the host)
            playHostReceiveHugSequence(other)
        } else if (newState == "visiting_$other") {
            // WE are visiting someone else (we are the visitor)
            playVisitorLeaveSequence(other)
        } else if (newState == "none" && oldState == "visiting_$other") {
            // We were visiting, now returning
            playVisitorReturnSequence()
        } else if (newState == "none" && oldState == "visiting_$role") {
            // We were hosting, hug broken
            playHostEndHugSequence()
        } else if (newState == "none") {
            applyIdleState()
            notifView.visibility = View.GONE
            visitorSprite.visibility = View.GONE
            spriteView.visibility = View.VISIBLE
        }
    }
    
    private fun playVisitorLeaveSequence(other: String) {
        isAnimating = true
        val walkRes = if (role == "blue") R.drawable.blue_walk else R.drawable.red_walk
        val ghostRes = if (role == "blue") R.drawable.blue_ghost else R.drawable.red_ghost
        
        // Walk off screen
        val destX = if (role == "red") -400 else screenWidth + 400
        val faceLeft = role == "red"
        
        spriteView.setSprite(walkRes, true, 100L, 2.5f)
        spriteView.setFacingLeft(faceLeft)
        
        val anim = ObjectAnimator.ofInt(this, "windowX", params.x, destX)
        anim.duration = 2000
        anim.start()
        
        serviceScope.launch {
            delay(2000)
            // Turn into ghost and teleport back
            params.x = if (role == "blue") -150 else screenWidth - 220
            windowManager.updateViewLayout(container, params)
            spriteView.setSprite(ghostRes, true, 150L, 2.5f)
            isAnimating = false
        }
    }
    
    private fun playVisitorReturnSequence() {
        isAnimating = true
        val walkRes = if (role == "blue") R.drawable.blue_walk else R.drawable.red_walk
        
        // Walk back on screen
        val startX = if (role == "red") -400 else screenWidth + 400
        val destX = if (role == "blue") -150 else screenWidth - 220
        val faceLeft = role == "blue" // Coming back from opposite dir
        
        params.x = startX
        windowManager.updateViewLayout(container, params)
        
        spriteView.setSprite(walkRes, true, 100L, 2.5f)
        spriteView.setFacingLeft(faceLeft)
        
        val anim = ObjectAnimator.ofInt(this, "windowX", startX, destX)
        anim.duration = 2000
        anim.start()
        
        serviceScope.launch {
            delay(2000)
            applyIdleState()
            isAnimating = false
        }
    }
    
    private fun playHostReceiveHugSequence(visitorRole: String) {
        isAnimating = true
        val walkRes = if (visitorRole == "blue") R.drawable.blue_walk else R.drawable.red_walk
        val hugRes = if (role == "blue") R.drawable.blue_newhug else R.drawable.red_newhug // The host dictates the hug image we use! Actually wait, I mapped blue_newhug for host blue.
        
        visitorSprite.visibility = View.VISIBLE
        val faceLeft = role == "blue" // Visitor arrives from opposite side
        visitorSprite.setSprite(walkRes, true, 100L, 2.5f)
        visitorSprite.setFacingLeft(faceLeft)
        
        val startX = if (role == "blue") 400f else -400f
        visitorSprite.translationX = startX
        visitorSprite.translationY = -50f // Vertically above
        
        val anim = ObjectAnimator.ofFloat(visitorSprite, "translationX", startX, 50f)
        anim.duration = 2000
        anim.start()
        
        serviceScope.launch {
            delay(2000)
            // HUG
            spriteView.visibility = View.GONE // Hide host
            visitorSprite.setSprite(hugRes, false, scale = 3.5f)
            visitorSprite.translationX = 0f
            visitorSprite.translationY = -80f
            // If we are blue host, we want blue on left, so NO flip.
            visitorSprite.setFacingLeft(role == "red")
            isAnimating = false
        }
    }
    
    private fun playHostEndHugSequence() {
        isAnimating = true
        val visitorRole = if (role == "blue") "red" else "blue"
        val walkRes = if (visitorRole == "blue") R.drawable.blue_walk else R.drawable.red_walk
        
        applyIdleState()
        spriteView.visibility = View.VISIBLE
        
        val faceLeft = role == "red" // Leave in opposite dir
        visitorSprite.setSprite(walkRes, true, 100L, 2.5f)
        visitorSprite.setFacingLeft(faceLeft)
        
        val destX = if (role == "blue") 400f else -400f
        val anim = ObjectAnimator.ofFloat(visitorSprite, "translationX", 50f, destX)
        anim.duration = 2000
        anim.start()
        
        serviceScope.launch {
            delay(2000)
            visitorSprite.visibility = View.GONE
            isAnimating = false
        }
    }

    // Used by ObjectAnimator
    fun setWindowX(x: Int) {
        params.x = x
        windowManager.updateViewLayout(container, params)
    }
    fun getWindowX(): Int = params.x

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (::windowManager.isInitialized) {
            windowManager.removeView(container)
        }
    }
}

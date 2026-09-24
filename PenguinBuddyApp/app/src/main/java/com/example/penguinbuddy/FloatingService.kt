package com.example.penguinbuddy

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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

class FloatingService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var container: FrameLayout
    private lateinit var spriteView: SpriteView
    private lateinit var notifView: TextView
    private lateinit var visitorSprite: SpriteView
    private lateinit var params: WindowManager.LayoutParams
    private var visitorParams: WindowManager.LayoutParams? = null
    
    private var role = "blue"
    private var activeState = "none"
    private val client = OkHttpClient()
    private val serverUrl = "https://kvdb.io/44FNGEYy2QA8MqFD7Y6PAc/state"
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    
    private var screenWidth = 0
    private var isAnimating = false
    private var lastPushTime = 0L
    private var ghostBobAnimator: ObjectAnimator? = null

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
        
        spriteView = SpriteView(this).apply {
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = 120 
            layoutParams = lp
        }
        notifView = TextView(this).apply {
            text = "❗️"
            textSize = 40f
            setTextColor(Color.RED)
            visibility = View.GONE
            translationX = 100f
        }
        
        visitorSprite = SpriteView(this)
        
        container.addView(spriteView)
        container.addView(notifView)

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
                        if (diffX < 50 && diffY < 50) { 
                            val clickTime = System.currentTimeMillis()
                            if (clickTime - lastClickTime < 500) { 
                                onDoubleTap()
                                lastClickTime = 0L 
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
            if (isAnimating) return@setOnClickListener
            onSingleTap()
        }
    }
    
    private fun applyIdleState() {
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = if (role == "blue") -150 else screenWidth - 220
        params.y = 500
        container.rotation = if (role == "blue") 10f else -10f
        windowManager.updateViewLayout(container, params)
        
        spriteView.translationY = 0f
        ghostBobAnimator?.cancel()
        
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
                    
                    if (newState != activeState && System.currentTimeMillis() - lastPushTime > 2000) {
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
        if (state == activeState) return // Prevent spamming duplicate states
        lastPushTime = System.currentTimeMillis()
        handleStateChange(state)
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
        } else if (activeState == "visiting_$role") {
            pushState("hugging_$role")
        } else if (activeState == "hugging_$role") {
            pushState("none")
        }
    }

    private fun handleStateChange(newState: String) {
        val oldState = activeState
        if (oldState == newState) return // Extra guard against multiple triggers
        activeState = newState
        val other = if (role == "blue") "red" else "blue"

        if (newState == "waiting_$role") {
            val res = if (role == "blue") R.drawable.blue_sad else R.drawable.red_sad
            spriteView.setSprite(res, false, scale = 2.5f)
        } else if (newState == "waiting_$other") {
            notifView.visibility = View.VISIBLE
        } else if (newState == "visiting_$role") {
            playHostReceiveVisitorSequence(other)
        } else if (newState == "visiting_$other") {
            playVisitorLeaveSequence(other)
        } else if (newState == "hugging_$role") {
            playHostHugSequence()
        } else if (newState == "none" && (oldState == "visiting_$other" || oldState == "hugging_$other")) {
            playVisitorReturnSequence()
        } else if (newState == "none" && (oldState == "visiting_$role" || oldState == "hugging_$role")) {
            playHostEndHugSequence()
        } else if (newState == "none") {
            applyIdleState()
            notifView.visibility = View.GONE
            if (visitorSprite.parent != null) windowManager.removeView(visitorSprite)
            spriteView.visibility = View.VISIBLE
        }
    }
    
    private fun playVisitorLeaveSequence(other: String) {
        isAnimating = true
        val walkRes = if (role == "blue") R.drawable.blue_walk else R.drawable.red_walk
        val ghostRes = if (role == "blue") R.drawable.blue_ghost else R.drawable.red_ghost
        
        val destX = if (role == "red") -400 else screenWidth + 400
        val faceLeft = role == "red"
        
        spriteView.setSprite(walkRes, true, 100L, 2.5f)
        spriteView.setFacingLeft(faceLeft)
        
        val anim = ObjectAnimator.ofInt(this, "windowX", params.x, destX)
        anim.duration = 2000
        anim.start()
        
        serviceScope.launch {
            delay(2000)
            params.x = if (role == "blue") 150 else screenWidth - 350
            container.rotation = 0f
            windowManager.updateViewLayout(container, params)
            spriteView.setSprite(ghostRes, true, 150L, 2.5f)
            
            ghostBobAnimator = ObjectAnimator.ofFloat(spriteView, "translationY", 0f, -30f, 0f)
            ghostBobAnimator?.duration = 2000
            ghostBobAnimator?.repeatCount = ValueAnimator.INFINITE
            ghostBobAnimator?.start()
            
            isAnimating = false
        }
    }
    
    private fun playVisitorReturnSequence() {
        isAnimating = true
        ghostBobAnimator?.cancel()
        spriteView.translationY = 0f
        
        val walkRes = if (role == "blue") R.drawable.blue_walk else R.drawable.red_walk
        
        val startX = if (role == "red") -400 else screenWidth + 400
        val destX = if (role == "blue") -150 else screenWidth - 220
        val faceLeft = role == "blue" 
        
        params.x = startX
        container.rotation = if (role == "blue") 10f else -10f
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
    
    private fun playHostReceiveVisitorSequence(visitorRole: String) {
        isAnimating = true
        val walkRes = if (visitorRole == "blue") R.drawable.blue_walk else R.drawable.red_walk
        
        visitorParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        val vp = visitorParams!!
        vp.gravity = Gravity.TOP or Gravity.LEFT
        val startX = if (role == "blue") screenWidth + 200 else -400
        vp.x = startX
        vp.y = params.y
        if (visitorSprite.parent == null) windowManager.addView(visitorSprite, vp)
        
        val faceLeft = role == "blue"
        visitorSprite.setSprite(walkRes, true, 100L, 2.5f)
        visitorSprite.setFacingLeft(faceLeft)
        
        val destX = params.x + if (role == "blue") 250 else -250
        val anim = ValueAnimator.ofInt(startX, destX)
        anim.addUpdateListener {
            vp.x = it.animatedValue as Int
            if (visitorSprite.parent != null) windowManager.updateViewLayout(visitorSprite, vp)
        }
        anim.duration = 2000
        anim.start()
        
        serviceScope.launch {
            delay(2000)
            visitorSprite.setSprite(if (visitorRole == "blue") R.drawable.blue_idle else R.drawable.red_idle, false, scale = 2.5f)
            isAnimating = false
        }
    }
    
    private fun playHostHugSequence() {
        isAnimating = true
        val hugRes = if (role == "blue") R.drawable.blue_newhug else R.drawable.red_newhug 
        
        spriteView.visibility = View.INVISIBLE 
        visitorSprite.setSprite(hugRes, false, scale = 3.5f)
        
        val vp = visitorParams!!
        vp.x = params.x + if (role == "blue") -50 else -20
        vp.y = params.y - 120
        visitorSprite.setFacingLeft(role == "red")
        if (visitorSprite.parent != null) windowManager.updateViewLayout(visitorSprite, vp)
        
        isAnimating = false
    }
    
    private fun playHostEndHugSequence() {
        isAnimating = true
        val visitorRole = if (role == "blue") "red" else "blue"
        val walkRes = if (visitorRole == "blue") R.drawable.blue_walk else R.drawable.red_walk
        
        applyIdleState()
        spriteView.visibility = View.VISIBLE
        
        val vp = visitorParams ?: return
        val startX = vp.x
        val destX = if (role == "blue") screenWidth + 400 else -400
        
        val faceLeft = role == "red"
        visitorSprite.setSprite(walkRes, true, 100L, 2.5f)
        visitorSprite.setFacingLeft(faceLeft)
        vp.y = params.y
        
        val anim = ValueAnimator.ofInt(startX, destX)
        anim.addUpdateListener {
            vp.x = it.animatedValue as Int
            if (visitorSprite.parent != null) windowManager.updateViewLayout(visitorSprite, vp)
        }
        anim.duration = 2000
        anim.start()
        
        serviceScope.launch {
            delay(2000)
            if (visitorSprite.parent != null) windowManager.removeView(visitorSprite)
            isAnimating = false
        }
    }

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
            if (visitorSprite.parent != null) windowManager.removeView(visitorSprite)
        }
    }
}

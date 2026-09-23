package com.example.penguinbuddy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View

class SpriteView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var spriteBitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    private val frameCount = 4
    private var currentFrame = 0
    private var frameWidth = 0
    private var frameHeight = 0
    
    private var isPlaying = false
    private var frameDelayMs = 150L
    private val handler = Handler(Looper.getMainLooper())
    
    private var facingLeft = false
    private var scaleFactor = 2f
    
    private val runnable = object : Runnable {
        override fun run() {
            if (!isPlaying || spriteBitmap == null) return
            currentFrame = (currentFrame + 1) % frameCount
            invalidate()
            handler.postDelayed(this, frameDelayMs)
        }
    }
    
    fun setSprite(resId: Int, isAnimated: Boolean, delay: Long = 150L, scale: Float = 2f) {
        val options = BitmapFactory.Options()
        options.inScaled = false
        spriteBitmap = BitmapFactory.decodeResource(resources, resId, options)
        
        spriteBitmap?.let { bmp ->
            frameWidth = bmp.width / frameCount
            frameHeight = bmp.height
            scaleFactor = scale
            requestLayout()
        }
        
        currentFrame = 0
        frameDelayMs = delay
        
        isPlaying = isAnimated
        handler.removeCallbacks(runnable)
        if (isPlaying) {
            handler.post(runnable)
        }
        invalidate()
    }
    
    fun setFacingLeft(left: Boolean) {
        facingLeft = left
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (frameWidth == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val desiredWidth = (frameWidth * scaleFactor).toInt()
        val desiredHeight = (frameHeight * scaleFactor).toInt()
        setMeasuredDimension(desiredWidth, desiredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = spriteBitmap ?: return
        
        val srcRect = Rect(
            currentFrame * frameWidth,
            0,
            (currentFrame + 1) * frameWidth,
            frameHeight
        )
        
        val destRect = Rect(0, 0, width, height)
        
        canvas.save()
        if (facingLeft) {
            canvas.scale(-1f, 1f, width / 2f, height / 2f)
        }
        canvas.drawBitmap(bmp, srcRect, destRect, paint)
        canvas.restore()
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(runnable)
    }
}

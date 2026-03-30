package com.example.mobilelens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Process
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.os.HandlerThread
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.widget.ImageView
class LensService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var magnifierCard: View
    private lateinit var magnifierSurface: SurfaceView
    private var surfaceHolder: SurfaceHolder? = null
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    
    private var lastFrameTime = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    // Persistent buffers for zero-allocation rendering
    private var fullBitmap: Bitmap? = null
    private var magnifiedBitmap: Bitmap? = null
    private var magnifiedCanvas: Canvas? = null
    
    private var isCapturing = false
    
    // Zoom factor (e.g., 2.0x)
    // Zoom factor (e.g., 1.15x) so you can read full lines from start to finish
    private var zoomFactor = 1.15f

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "Shared App session ended. Vanishing lens...")
            isCapturing = false
            stopSelf() // Stop the service and remove the lens automatically
        }
    }

    companion object {
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "LensServiceChannel"
        private const val TAG = "LensService"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        
        val oldW = screenWidth
        val oldH = screenHeight
        refreshMetrics()
        
        // Only restart the capture if the physical dimensions actually changed (e.g., Rotation)
        if (screenWidth != oldW || screenHeight != oldH) {
            Log.d(TAG, "Dimensions changed (${oldW}x${oldH} -> ${screenWidth}x${screenHeight}). Restarting...")
            restartCapture()
        }
    }

    private fun refreshMetrics() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            // Increase to 10s for more stable "Patience" during app transitions
            if (isCapturing && now - lastFrameTime > 10000) { 
                Log.d(TAG, "Watchdog: Total silence for 10s. Trying to wake up...")
                restartCapture()
            }
            if (isCapturing) mainHandler.postDelayed(this, 12000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        backgroundThread = HandlerThread("LensBackgroundThread", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        
        setupOverlayWindow()
    }

    private fun setupOverlayWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        val themedContext = ContextThemeWrapper(this, R.style.Theme_MobileLens)
        val inflater = LayoutInflater.from(themedContext)
        overlayView = inflater.inflate(R.layout.overlay_layout, null)
        magnifierCard = overlayView!!.findViewById(R.id.magnifier_card)
        magnifierSurface = overlayView!!.findViewById(R.id.magnifier_surface)

        // Setup the surface for high-speed background rendering
        magnifierSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceHolder = holder
                
                // Ensure rounded corners on the surface view
                magnifierSurface.clipToOutline = true
                magnifierSurface.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        val radius = if (magnifierCard.width > 0) (24 * screenDensity / 160).toFloat() else 0f
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                surfaceHolder = holder
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceHolder = null
            }
        })
        
        // Get screen metrics
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 500
        }

        setupDragListener(layoutParams)
        
        windowManager.addView(overlayView, layoutParams)
    }

    private fun setupDragListener(lp: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        magnifierCard.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = initialX + (event.rawX - initialTouchX).toInt()
                    lp.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, lp)
                    true
                }
                else -> false
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        
        // Start foreground with media projection type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != 0 && resultData != null) {
            Log.d(TAG, "Foreground service started with valid MediaProjection intent data.")
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            
            startCapture()
            
            // Start the watchdog
            mainHandler.postDelayed(watchdogRunnable, 5000)
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mobile Lens Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running mobile lens in the background"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mobile Lens")
            .setContentText("Screen magnifying service is running.")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // System placeholder icon
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun restartCapture() {
        Log.d(TAG, "Executing Zero-G restart...")
        
        try {
            isCapturing = false // CRITICAL: Allow startCapture to run again
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Restart cleanup error: ${e.message}")
        }
        
        if (mediaProjection != null) {
            startCapture()
        }
    }

    private fun startCapture() {
        if (isCapturing) return
        isCapturing = true
        
        // Enable Hardware Buffer usage for Zero-Copy GPU wrapping (API 29+)
        imageReader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ImageReader.newInstance(
                screenWidth, 
                screenHeight, 
                PixelFormat.RGBA_8888, 
                3, 
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_RARELY
            )
        } else {
            ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        }
        
        // Use background handler for image processing to eliminate lag
        imageReader?.setOnImageAvailableListener({ reader ->
            updateMagnifier(reader)
        }, backgroundHandler)
        
        // Android 14+ requires registering a callback before creating a VirtualDisplay
        mediaProjection?.registerCallback(projectionCallback, backgroundHandler)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "LensCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun updateMagnifier(reader: ImageReader) {
        if (!isCapturing) return
        
        // Mark as "Active" whenever the system tries to send us a frame
        lastFrameTime = System.currentTimeMillis()
        
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
        if (image == null) return
        
        val holder = surfaceHolder // Local copy for safety
        
        try {
            // ZERO-COPY UPGRADE: Wrap the GPU hardware buffer directly! (No CPU memory copying)
            val hwBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) image.hardwareBuffer else null
            val sourceBitmap = if (hwBuffer != null) {
                Bitmap.wrapHardwareBuffer(hwBuffer, ColorSpace.get(ColorSpace.Named.SRGB))
            } else {
                // Fallback for older devices/non-HW frames
                val planes = image.planes
                if (planes.isEmpty()) return
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val actualBitmapWidth = rowStride / pixelStride
                if (actualBitmapWidth < screenWidth) return
                if (fullBitmap == null || fullBitmap!!.width != actualBitmapWidth) {
                    fullBitmap = Bitmap.createBitmap(actualBitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
                }
                fullBitmap!!.copyPixelsFromBuffer(buffer)
                fullBitmap
            }

            if (sourceBitmap == null) return
            
            val location = IntArray(2)
            magnifierCard.getLocationOnScreen(location)
            val vX = location[0]
            val vY = location[1]
            val vW = magnifierCard.width
            val vH = magnifierCard.height
            
            if (vW > 0 && vH > 0 && holder != null) {
                val cropW = (vW / zoomFactor).toInt()
                val cropH = (vH / zoomFactor).toInt()
                val cropX = (vX + (vW - cropW) / 2).coerceIn(0, (screenWidth - cropW).coerceAtLeast(0))
                val cropY = (vY + (vH - cropH) / 2).coerceIn(0, (screenHeight - cropH).coerceAtLeast(0))
                
                if (cropW > 0 && cropH > 0) {
                    val srcRect = Rect(cropX, cropY, cropX + cropW, cropY + cropH)
                    val dstRect = Rect(0, 0, vW, vH)
                    
                    val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        holder.lockHardwareCanvas()
                    } else {
                        holder.lockCanvas()
                    }
                    
                    if (canvas != null) {
                        try {
                            canvas.drawBitmap(sourceBitmap, srcRect, dstRect, null)
                        } finally {
                            holder.unlockCanvasAndPost(canvas)
                        }
                    }
                }
            }
            
            // Note: wrapHardwareBuffer Bitmaps do not need manual recycling but the HardwareBuffer itself should be closed
            hwBuffer?.close()
            
        } catch (e: Exception) {
            Log.e(TAG, "Hardware Zero-Copy processing error: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun stopCapture() {
        if (!isCapturing) return
        isCapturing = false
        
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
            
            mainHandler.removeCallbacks(watchdogRunnable)
            
            fullBitmap = null
            magnifiedBitmap = null
            magnifiedCanvas = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capture: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        backgroundThread?.quitSafely()
        
        overlayView?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
        }
        Log.d(TAG, "LensService destroyed.")
    }
}

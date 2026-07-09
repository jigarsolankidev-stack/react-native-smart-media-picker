package com.smartmediapicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

class SmartMediaPickerCropActivity : Activity() {

    private lateinit var cropView: CropView
    private var imageUri: Uri? = null
    private var aspectRatioX: Int = 0
    private var aspectRatioY: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Parse Intent Options
        val uriStr = intent.getStringExtra("imageUri")
        if (uriStr == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        imageUri = Uri.parse(uriStr)
        aspectRatioX = intent.getIntExtra("aspectRatioX", 0)
        aspectRatioY = intent.getIntExtra("aspectRatioY", 0)

        // Load Bitmap safely
        val bitmap = loadBitmapFromUri(imageUri!!)
        if (bitmap == null) {
            Toast.makeText(this, "Failed to load image for cropping", Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        // Layout creation programmatically (so no XML layout is required)
        val rootLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        cropView = CropView(this, bitmap, aspectRatioX, aspectRatioY).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(cropView)

        // Bottom Button Controls
        val controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                setMargins(16, 16, 16, 48)
            }
        }

        val btnCancel = Button(this).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
            ).apply {
                setMargins(8, 8, 8, 8)
            }
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }

        val btnRotate = Button(this).apply {
            text = "Rotate"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
            ).apply {
                setMargins(8, 8, 8, 8)
            }
            setOnClickListener {
                cropView.rotateImage90()
            }
        }

        val btnCrop = Button(this).apply {
            text = "Crop"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#007AFF"))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
            ).apply {
                setMargins(8, 8, 8, 8)
            }
            setOnClickListener {
                val cropped = cropView.getCroppedBitmap()
                if (cropped != null) {
                    val croppedUri = saveCroppedBitmap(cropped)
                    if (croppedUri != null) {
                        val data = Intent().apply {
                            putExtra("croppedImageUri", croppedUri.toString())
                        }
                        setResult(RESULT_OK, data)
                        finish()
                    } else {
                        Toast.makeText(this@SmartMediaPickerCropActivity, "Failed to save cropped image", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@SmartMediaPickerCropActivity, "Failed to crop image", Toast.LENGTH_SHORT).show()
                }
            }
        }

        controlsLayout.addView(btnCancel)
        controlsLayout.addView(btnRotate)
        controlsLayout.addView(btnCrop)
        rootLayout.addView(controlsLayout)

        setContentView(rootLayout)
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun saveCroppedBitmap(bitmap: Bitmap): Uri? {
        return try {
            val cacheFile = File(cacheDir, "cropped_image_${System.currentTimeMillis()}.jpg")
            val outputStream = FileOutputStream(cacheFile)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            outputStream.flush()
            outputStream.close()
            Uri.fromFile(cacheFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Custom view that supports Pinch-to-Zoom, Panning, and aspect ratio crop overlays
    private class CropView(
        context: Context,
        private var bitmap: Bitmap,
        private val aspectX: Int,
        private val aspectY: Int
    ) : View(context) {

        private val matrix = Matrix()
        private val savedMatrix = Matrix()

        private val start = PointF()
        private val mid = PointF()
        private var oldDist = 1f

        private var mode = NONE

        private val scaleDetector: ScaleGestureDetector
        private val cropRect = RectF()

        private val borderPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 5f
            isAntiAlias = true
        }

        private val overlayPaint = Paint().apply {
            color = Color.parseColor("#88000000")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        companion object {
            private const val NONE = 0
            private const val DRAG = 1
            private const val ZOOM = 2
        }

        init {
            scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val scaleFactor = detector.scaleFactor
                    matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                    invalidate()
                    return true
                }
            })
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            setupInitialImagePosition(w, h)
            setupCropRectangle(w, h)
        }

        private fun setupInitialImagePosition(w: Int, h: Int) {
            matrix.reset()
            val scale = min(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height) * 0.8f
            matrix.postScale(scale, scale)
            val dx = (w - bitmap.width * scale) / 2f
            val dy = (h - bitmap.height * scale) / 2f
            matrix.postTranslate(dx, dy)
        }

        private fun setupCropRectangle(w: Int, h: Int) {
            val padding = 60f
            val maxW = w - padding * 2
            val maxH = h - padding * 2

            if (aspectX > 0 && aspectY > 0) {
                // Constrained aspect ratio
                val targetRatio = aspectX.toFloat() / aspectY.toFloat()
                var cropW = maxW
                var cropH = cropW / targetRatio
                if (cropH > maxH) {
                    cropH = maxH
                    cropW = cropH * targetRatio
                }
                val left = (w - cropW) / 2
                val top = (h - cropH) / 2
                cropRect.set(left, top, left + cropW, top + cropH)
            } else {
                // Free style square/rect fallback
                val size = min(maxW, maxH) * 0.9f
                val left = (w - size) / 2
                val top = (h - size) / 2
                cropRect.set(left, top, left + size, top + size)
            }
        }

        fun rotateImage90() {
            val rotMatrix = Matrix().apply { postRotate(90f) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, rotMatrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
                bitmap = rotated
            }
            setupInitialImagePosition(width, height)
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)

            val x = event.x
            val y = event.y

            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    savedMatrix.set(matrix)
                    start.set(x, y)
                    mode = DRAG
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    oldDist = spacing(event)
                    if (oldDist > 10f) {
                        savedMatrix.set(matrix)
                        midPoint(mid, event)
                        mode = ZOOM
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    mode = NONE
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == DRAG) {
                        matrix.set(savedMatrix)
                        matrix.postTranslate(x - start.x, y - start.y)
                    } else if (mode == ZOOM && event.pointerCount >= 2) {
                        val newDist = spacing(event)
                        if (newDist > 10f) {
                            matrix.set(savedMatrix)
                            val scale = newDist / oldDist
                            matrix.postScale(scale, scale, mid.x, mid.y)
                        }
                    }
                }
            }
            invalidate()
            return true
        }

        private fun spacing(event: MotionEvent): Float {
            val x = event.getX(0) - event.getX(1)
            val y = event.getY(0) - event.getY(1)
            return kotlin.math.sqrt((x * x + y * y).toDouble()).toFloat()
        }

        private fun midPoint(point: PointF, event: MotionEvent) {
            val x = event.getX(0) + event.getX(1)
            val y = event.getY(0) + event.getY(1)
            point.set(x / 2, y / 2)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Draw current scaled/translated image
            canvas.drawBitmap(bitmap, matrix, null)

            // Draw translucent overlays outside crop overlay
            // Top overlay
            canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, overlayPaint)
            // Bottom overlay
            canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), overlayPaint)
            // Left overlay
            canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, overlayPaint)
            // Right overlay
            canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, overlayPaint)

            // Draw Crop Border
            canvas.drawRect(cropRect, borderPaint)
        }

        fun getCroppedBitmap(): Bitmap? {
            return try {
                // Calculate matrix inverse to map crop coordinates back to source bitmap space
                val inverse = Matrix()
                matrix.invert(inverse)

                val mappedCrop = RectF()
                inverse.mapRect(mappedCrop, cropRect)

                // Clamp to bitmap boundaries
                val left = max(0, mappedCrop.left.toInt())
                val top = max(0, mappedCrop.top.toInt())
                val right = min(bitmap.width, mappedCrop.right.toInt())
                val bottom = min(bitmap.height, mappedCrop.bottom.toInt())

                val cropW = right - left
                val cropH = bottom - top

                if (cropW <= 0 || cropH <= 0) return null

                Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}

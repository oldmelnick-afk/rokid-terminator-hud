package com.rocketglasses.terminatorpreview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

/** Local, throttled visual analysis. The displayed attributes are game effects, not assessments. */
class VisionAnalyzer(
    context: Context,
    private val onSmile: (Float?) -> Unit,
    private val onMiddleFinger: () -> Unit
) {
    private val thread = HandlerThread("HudVision").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { command -> handler.post(command) }
    private val busy = AtomicBoolean(false)
    private var closed = false
    private var consecutiveGestureFrames = 0
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    )
    private var handLandmarker: HandLandmarker? = null

    init {
        handler.post {
            try {
                val base = BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .build()
                val options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setNumHands(2)
                    .setRunningMode(RunningMode.IMAGE)
                    .build()
                handLandmarker = HandLandmarker.createFromOptions(context, options)
            } catch (error: Exception) {
                Log.e(TAG, "Hand model unavailable", error)
            }
        }
    }

    fun submit(image: Image, rotationDegrees: Int) {
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        val bitmap = try {
            image.use { toBitmap(it, rotationDegrees) }
        } catch (error: Exception) {
            Log.e(TAG, "Camera frame conversion failed", error)
            busy.set(false)
            return
        }
        handler.post {
            if (closed) {
                bitmap.recycle()
                busy.set(false)
                return@post
            }
            try {
                val result = handLandmarker?.detect(BitmapImageBuilder(bitmap).build())
                val candidate = result?.landmarks()?.any(::isMiddleFinger) == true
                consecutiveGestureFrames = if (candidate) consecutiveGestureFrames + 1 else 0
                if (consecutiveGestureFrames >= 2) onMiddleFinger()
            } catch (error: Exception) {
                Log.e(TAG, "Hand analysis failed", error)
                consecutiveGestureFrames = 0
            }
            try {
                faceDetector.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnCompleteListener(executor) { task ->
                        if (!closed && task.isSuccessful) {
                            val face = task.result.maxByOrNull {
                                it.boundingBox.width() * it.boundingBox.height()
                            }
                            onSmile(face?.smilingProbability)
                        }
                        bitmap.recycle()
                        busy.set(false)
                    }
            } catch (error: Exception) {
                Log.e(TAG, "Smile analysis failed", error)
                bitmap.recycle()
                busy.set(false)
            }
        }
    }

    fun close() {
        handler.post {
            closed = true
            handLandmarker?.close()
            faceDetector.close()
            thread.quitSafely()
        }
    }

    private fun isMiddleFinger(points: List<NormalizedLandmark>): Boolean {
        if (points.size < 21) return false
        fun reach(mcp: Int, pip: Int, tip: Int): Float {
            val base = hypot(points[pip].x() - points[mcp].x(),
                points[pip].y() - points[mcp].y())
            if (base < 0.001f) return 0f
            val extension = hypot(points[tip].x() - points[mcp].x(),
                points[tip].y() - points[mcp].y())
            return extension / base
        }
        return reach(9, 10, 12) > 1.9f &&
            reach(5, 6, 8) < 1.55f &&
            reach(13, 14, 16) < 1.55f &&
            reach(17, 18, 20) < 1.55f
    }

    private fun toBitmap(image: Image, rotationDegrees: Int): Bitmap {
        val width = image.width
        val height = image.height
        val uvWidth = width / 2
        val uvHeight = height / 2
        val nv21 = ByteArray(width * height + uvWidth * uvHeight * 2)
        fun copyPlane(planeIndex: Int, outputOffset: Int, stride: Int,
                      planeWidth: Int, planeHeight: Int) {
            val plane = image.planes[planeIndex]
            val buffer = plane.buffer
            for (row in 0 until planeHeight) {
                for (column in 0 until planeWidth) {
                    nv21[outputOffset + row * planeWidth * stride + column * stride] =
                        buffer.get(row * plane.rowStride + column * plane.pixelStride)
                }
            }
        }
        copyPlane(0, 0, 1, width, height)
        copyPlane(2, width * height, 2, uvWidth, uvHeight)
        copyPlane(1, width * height + 1, 2, uvWidth, uvHeight)
        val jpeg = ByteArrayOutputStream()
        check(YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), 78, jpeg))
        val bytes = jpeg.toByteArray()
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Frame decode failed")
        if (rotationDegrees % 360 == 0) return source
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height,
            matrix, true)
        source.recycle()
        return rotated
    }

    companion object {
        private const val TAG = "HudVision"
    }
}

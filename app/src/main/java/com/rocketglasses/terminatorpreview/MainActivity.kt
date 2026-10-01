package com.rocketglasses.terminatorpreview

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.Face
import android.media.ImageReader
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import java.util.Locale

private data class FictionalProfile(
    val code: String,
    val sex: String,
    val race: String,
    val sector: String,
    val activity: String,
    val birthDay: String,
    val birthYear: Int,
    val address: String,
    val heightCm: Int,
    val massKg: Int
)

class MainActivity : Activity() {
    private lateinit var hud: PreviewHudView
    private lateinit var visionAnalyzer: VisionAnalyzer
    private val cameraThread = HandlerThread("HudCamera")
    private lateinit var cameraHandler: Handler
    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingCameraOpen: Runnable? = null
    private var cameraRetryDelayMs = 1_500L
    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    @Volatile private var cameraRequested = false
    private var lastVisionFrameAt = 0L
    private var lastSelectAt = 0L
    private lateinit var sensorManager: SensorManager
    private var headingSensor: Sensor? = null
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val headingListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val yawDegrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
            hud.updateHeading(yawDegrees,
                event.sensor.type == Sensor.TYPE_ROTATION_VECTOR)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 1
        private const val DOUBLE_TAP_WINDOW_MS = 400L
        private const val TAG = "TerminatorHud"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        hud = PreviewHudView(this)
        setContentView(hud)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        headingSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        Log.i(TAG, "Heading sensor: ${headingSensor?.name ?: "none"}")
        visionAnalyzer = VisionAnalyzer(applicationContext,
            onSmile = { probability ->
                runOnUiThread { if (cameraRequested) hud.updateSmile(probability) }
            },
            onMiddleFinger = {
                runOnUiThread { if (cameraRequested) hud.reportMiddleFinger() }
            }
        )
        cameraThread.start()
        cameraHandler = Handler(cameraThread.looper)
    }

    override fun onResume() {
        super.onResume()
        headingSensor?.let {
            sensorManager.registerListener(headingListener, it,
                SensorManager.SENSOR_DELAY_UI, uiHandler)
        }
        cameraRequested = true
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scheduleCameraOpen(300L)
        } else {
            hud.setCameraStatus("CAMERA PERMISSION REQUIRED")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    override fun onPause() {
        cameraRequested = false
        sensorManager.unregisterListener(headingListener)
        hud.resetHeadingSensor()
        pendingCameraOpen?.let(uiHandler::removeCallbacks)
        pendingCameraOpen = null
        closeCamera()
        hud.clearTarget()
        super.onPause()
    }

    override fun onDestroy() {
        visionAnalyzer.close()
        cameraThread.quitSafely()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST && cameraRequested) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                scheduleCameraOpen(300L)
            }
            else hud.setCameraStatus("CAMERA PERMISSION DENIED")
        }
    }

    private fun scheduleCameraOpen(delayMs: Long) {
        pendingCameraOpen?.let(uiHandler::removeCallbacks)
        val task = Runnable {
            pendingCameraOpen = null
            if (cameraRequested && cameraDevice == null) openCamera()
        }
        pendingCameraOpen = task
        uiHandler.postDelayed(task, delayMs)
    }

    private fun openCamera() {
        if (!cameraRequested || cameraDevice != null) return
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: error("No rear camera")
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val modes = characteristics.get(
                CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES
            ) ?: intArrayOf()
            val faceMode = when {
                modes.contains(CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE) ->
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
                modes.contains(CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL) ->
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL
                else -> error("Camera has no face detection")
            }
            val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?: error("Camera active array unavailable")
            val sensorOrientation =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val sensorMm = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val sensorPixels = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val focalMm = characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )?.firstOrNull()
            val focalPixels = if (sensorMm != null && sensorPixels != null &&
                focalMm != null && sensorMm.width > 0f
            ) focalMm * sensorPixels.width / sensorMm.width else null
            val sizes = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )?.getOutputSizes(ImageFormat.YUV_420_888) ?: error("No camera output size")
            val size = sizes.minByOrNull {
                abs(it.width - 640) + abs(it.height - 480)
            } ?: error("No camera output size")
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener({ source ->
                val frame = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                val now = SystemClock.elapsedRealtime()
                if (!cameraRequested || now - lastVisionFrameAt < 700L) {
                    frame.close()
                } else {
                    lastVisionFrameAt = now
                    visionAnalyzer.submit(frame, sensorOrientation)
                }
            }, cameraHandler)
            imageReader = reader
            hud.setCameraStatus("CAMERA STARTING")
            @Suppress("MissingPermission")
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!cameraRequested) {
                        device.close()
                        return
                    }
                    cameraRetryDelayMs = 1_500L
                    cameraDevice = device
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceMode)
                        }.build()
                        device.createCaptureSession(listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    if (!cameraRequested) {
                                        session.close()
                                        return
                                    }
                                    cameraSession = session
                                    try {
                                        session.setRepeatingRequest(request,
                                            object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    result: TotalCaptureResult
                                                ) {
                                                    val faces = result.get(CaptureResult.STATISTICS_FACES)
                                                    val best = faces?.maxByOrNull {
                                                        it.bounds.width() * it.bounds.height()
                                                    }
                                                    runOnUiThread {
                                                        if (!cameraRequested) return@runOnUiThread
                                                        hud.setCameraStatus("CAMERA ACTIVE")
                                                        hud.updateFace(
                                                            best, active, sensorOrientation, focalPixels
                                                        )
                                                    }
                                                }
                                            }, cameraHandler)
                                    } catch (e: Exception) { cameraFailure(e) }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    cameraFailure(IllegalStateException("Camera session failed"))
                                }
                            }, cameraHandler)
                    } catch (e: Exception) { cameraFailure(e) }
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                    cameraFailure(IllegalStateException("Camera disconnected"))
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    cameraDevice = null
                    cameraFailure(IllegalStateException("Camera error $error"))
                }
            }, cameraHandler)
        } catch (e: Exception) { cameraFailure(e) }
    }

    private fun closeCamera() {
        cameraSession?.close()
        cameraSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    private fun cameraFailure(error: Exception) {
        Log.e(TAG, "Camera tracking unavailable", error)
        runOnUiThread {
            closeCamera()
            hud.setCameraStatus("CAMERA ERROR")
            hud.clearTarget()
            if (cameraRequested) {
                scheduleCameraOpen(cameraRetryDelayMs)
                cameraRetryDelayMs = (cameraRetryDelayMs * 2L).coerceAtMost(10_000L)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_SPACE
        ) {
            if (event?.repeatCount == 0) {
                val now = SystemClock.uptimeMillis()
                if (now - lastSelectAt <= DOUBLE_TAP_WINDOW_MS) {
                    lastSelectAt = 0L
                    exitToLauncher()
                } else {
                    lastSelectAt = now
                    hud.restartScan()
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun exitToLauncher() {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }

    private class PreviewHudView(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val hudGreen = Color.rgb(96, 255, 145)
        private var scanStartedAt = SystemClock.elapsedRealtime()
        private var lastFaceAt = 0L
        private var targetX = 240f
        private var targetY = 300f
        private var targetRadius = 80f
        private var targetDistanceM: Float? = null
        // Fictional dossier cards. They do not describe or identify the camera subject.
        private val profiles = listOf(
            FictionalProfile("P-01", "M", "HUMAN", "POWER", "ENGINEER", "14 SEP", 1986, "MSK-A17", 178, 76),
            FictionalProfile("P-02", "F", "HUMAN", "CIVIL", "COURIER", "02 MAR", 1998, "MSK-B04", 166, 61),
            FictionalProfile("P-03", "M", "CYBORG?", "SECURITY", "GUARD", "21 JUN", 1979, "MSK-C31", 184, 87),
            FictionalProfile("P-04", "F", "HUMAN", "MEDICAL", "MEDIC", "09 DEC", 1991, "MSK-D08", 171, 66),
            FictionalProfile("P-05", "M", "HUMAN", "TRANSIT", "DRIVER", "17 APR", 1983, "MSK-E22", 175, 79),
            FictionalProfile("P-06", "F", "CYBORG?", "INDUSTRY", "OPERATOR", "30 JAN", 2000, "MSK-F12", 169, 64),
            FictionalProfile("P-07", "M", "HUMAN", "CIVIL", "DRUMMER", "05 OCT", 1995, "MSK-G09", 181, 74),
            FictionalProfile("P-08", "F", "HUMAN", "POWER", "TECHNICIAN", "11 MAY", 1988, "MSK-H14", 173, 70),
            FictionalProfile("P-09", "M", "HUMAN", "SECURITY", "PATROL", "23 JUL", 1976, "MSK-J03", 187, 92),
            FictionalProfile("P-10", "F", "CYBORG?", "RESEARCH", "ANALYST", "06 FEB", 1993, "MSK-K26", 164, 59),
            FictionalProfile("P-11", "M", "HUMAN", "TRANSIT", "MECHANIC", "19 NOV", 1981, "MSK-L06", 176, 83),
            FictionalProfile("P-12", "F", "HUMAN", "MEDICAL", "PARAMEDIC", "27 AUG", 2002, "MSK-M19", 168, 63),
            FictionalProfile("P-13", "M", "CYBORG?", "INDUSTRY", "INSPECTOR", "03 JAN", 1990, "MSK-N11", 183, 85),
            FictionalProfile("P-14", "F", "HUMAN", "CIVIL", "TEACHER", "15 JUN", 1985, "MSK-P24", 170, 68),
            FictionalProfile("P-15", "M", "HUMAN", "POWER", "ELECTRICIAN", "08 APR", 1997, "MSK-R05", 179, 77),
            FictionalProfile("P-16", "F", "HUMAN", "RESEARCH", "SCIENTIST", "25 OCT", 1978, "MSK-S16", 172, 71)
        )
        private val remainingProfileIndices = mutableListOf<Int>()
        private var profile = profiles.first()
        private var smileProbability: Float? = null
        private var lastSmileAt = 0L
        private var lastMiddleFingerAt = 0L
        private var cameraStatus = "CAMERA STARTING"
        private val telemetryStartedAt = SystemClock.elapsedRealtime()
        private var lastTelemetryUpdateAt = 0L
        private var cpuValue = 31
        private var procValue = 3871
        private var busValue = 436
        private var cpuTail = 643
        private var procTail = 129
        private var busTail = 224
        private var cpuSub = 22
        private var procSub = 77
        private var busSub = 16
        private var addrHigh = 0xF2A6
        private var addrLow = 0xE8B1
        private var addrValue = 0xA6
        private var sensedHeading: Float? = null
        private var yawBaseline: Float? = null
        private var lastHeadingAt = 0L
        private val normal = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        private val bold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

        fun setCameraStatus(status: String) {
            cameraStatus = status
            invalidate()
        }

        fun clearTarget() {
            lastFaceAt = 0L
            targetDistanceM = null
            smileProbability = null
            lastSmileAt = 0L
            lastMiddleFingerAt = 0L
            invalidate()
        }

        fun updateHeading(yawDegrees: Float, absolute: Boolean) {
            if (!yawDegrees.isFinite()) return
            val heading = if (absolute) {
                normalizeDegrees(yawDegrees)
            } else {
                val baseline = yawBaseline ?: yawDegrees.also { yawBaseline = it }
                normalizeDegrees(166f + signedDegrees(yawDegrees - baseline))
            }
            sensedHeading = heading
            lastHeadingAt = SystemClock.elapsedRealtime()
            invalidate()
        }

        fun resetHeadingSensor() {
            yawBaseline = null
            sensedHeading = null
            lastHeadingAt = 0L
        }

        private fun normalizeDegrees(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

        private fun signedDegrees(degrees: Float): Float =
            ((degrees + 540f) % 360f + 360f) % 360f - 180f

        private fun nextProfile() {
            if (remainingProfileIndices.isEmpty()) {
                remainingProfileIndices.addAll(profiles.indices.shuffled())
            }
            profile = profiles[remainingProfileIndices.removeAt(0)]
        }

        fun updateSmile(probability: Float?) {
            if (probability != null) {
                smileProbability = smileProbability?.let { it * 0.6f + probability * 0.4f }
                    ?: probability
                lastSmileAt = SystemClock.elapsedRealtime()
            }
            invalidate()
        }

        fun reportMiddleFinger() {
            lastMiddleFingerAt = SystemClock.elapsedRealtime()
            invalidate()
        }

        fun updateFace(face: Face?, active: Rect, sensorOrientation: Int, focalPixels: Float?) {
            if (face == null) return
            val nx = ((face.bounds.exactCenterX() - active.left) / active.width())
                .coerceIn(0f, 1f)
            val ny = ((face.bounds.exactCenterY() - active.top) / active.height())
                .coerceIn(0f, 1f)
            val (screenX, screenY) = when (sensorOrientation) {
                90 -> ny to (1f - nx)
                180 -> (1f - nx) to (1f - ny)
                270 -> (1f - ny) to nx
                else -> nx to ny
            }
            val faceWidth = face.bounds.width().toFloat() / active.width() * 480f
            val faceHeight = face.bounds.height().toFloat() / active.height() * 640f
            val radius = (max(faceWidth, faceHeight) * 0.7f).coerceIn(42f, 145f)
            // The camera sits below the eye line; bias the aim toward the upper part of the face.
            val headAimY = (screenY * 640f - (35f + faceHeight * 0.12f)
                .coerceIn(35f, 65f)).coerceIn(96f, 390f)
            // A 15 cm face width is an assumption, not a measurement of this person.
            val facePixels = min(face.bounds.width(), face.bounds.height())
            val distanceM = if (focalPixels != null && facePixels > 0) {
                (0.15f * focalPixels / facePixels).coerceIn(0.2f, 9.9f)
            } else null
            val now = SystemClock.elapsedRealtime()
            val changedTarget = lastFaceAt > 0L && now - lastFaceAt <= 700L &&
                kotlin.math.hypot(screenX * 480f - targetX, headAimY - targetY) > 135f
            if (now - lastFaceAt > 700L || changedTarget) {
                targetX = screenX * 480f
                targetY = headAimY
                targetRadius = radius
                targetDistanceM = distanceM
                smileProbability = null
                lastSmileAt = 0L
                lastMiddleFingerAt = 0L
                nextProfile()
                scanStartedAt = now
            } else {
                val smoothing = 0.35f
                targetX += (screenX * 480f - targetX) * smoothing
                targetY += (headAimY - targetY) * smoothing
                targetRadius += (radius - targetRadius) * smoothing
                if (distanceM != null) {
                    targetDistanceM = targetDistanceM?.let {
                        it + (distanceM - it) * 0.2f
                    } ?: distanceM
                }
            }
            lastFaceAt = now
            invalidate()
        }

        fun restartScan() {
            scanStartedAt = SystemClock.elapsedRealtime()
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) restartScan()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.BLACK)
            canvas.save()
            canvas.scale(width / 480f, height / 640f)

            val now = SystemClock.elapsedRealtime()
            val targetVisible = lastFaceAt > 0L && now - lastFaceAt < 700L
            val scanElapsed = now - scanStartedAt
            val progress = if (targetVisible) {
                (scanElapsed.toFloat() / 4_000f).coerceIn(0f, 1f)
            } else 0f
            val profileReady = targetVisible && scanElapsed >= 5_000L
            val lockProgress = if (targetVisible) {
                ((scanElapsed - 5_000L) / 650f).coerceIn(0f, 1f)
            } else 0f
            val reticleRadius = targetRadius * (1f - lockProgress / 3f)
            val aggression = when {
                !profileReady -> null
                now - lastMiddleFingerAt < 2_500L -> 100
                now - lastSmileAt < 2_500L && smileProbability != null ->
                    (70f - 44f * smileProbability!!).toInt().coerceIn(26, 70)
                else -> 65
            }
            val battery = batteryPercent()

            txt(canvas, "ROCKET / TERMINATOR", 18f, 27f, 19f, true)
            line(canvas, 18f, 38f, 462f, 38f, 145, 1f)
            txt(canvas, if (targetVisible) "TARGET 01" else "NO TARGET", 18f, 61f, 14f)
            txt(canvas, "BAT ${if (battery >= 0) "$battery%" else "--"}", 376f, 61f, 13f)

            if (!targetVisible) center(canvas, "SEARCHING FOR FACE", 240f, 90f, 17f, true)
            else if (progress < 1f) center(canvas,
                "SCANNING ${(progress * 100).toInt()}%", 240f, 90f, 17f, true)
            else if (!profileReady) center(canvas, "SCAN COMPLETE", 240f, 90f, 17f, true)
            else center(canvas, "PRIMARY TARGET", 240f, 90f, 17f, true)

            if (targetVisible) {
                val labelX = targetX.coerceIn(103f, 377f)
                val labelY = (targetY - reticleRadius - 27f).coerceIn(122f, 352f)
                if (profileReady) center(canvas, "> JOHN CONNOR", labelX,
                    labelY, 20f, true)
                reticle(canvas, targetX, targetY, reticleRadius)
                if (progress < 1f) {
                    val sweepY = targetY - reticleRadius + progress * reticleRadius * 2f
                    line(canvas, targetX - reticleRadius * 0.8f, sweepY,
                        targetX + reticleRadius * 0.8f, sweepY, 50, 2f)
                }
            }
            diagnostics(canvas, now)
            val azimuth = compass(canvas, 395f, 263f, now)
            val drift = (now - telemetryStartedAt) / 1_000.0
            txt(canvas, "AZIMUTH ${azimuth.toString().padStart(3, '0')}", 311f, 339f, 12f, true)
            txt(canvas, String.format(Locale.US, "LAT  %.4f", 55.7558 +
                sin(drift / 7.0) * 0.00008), 311f, 357f, 12f)
            txt(canvas, String.format(Locale.US, "LON  %.4f", 37.6173 +
                cos(drift / 9.0) * 0.00008), 311f, 375f, 12f)

            line(canvas, 18f, 393f, 462f, 393f, 145, 1f)
            txt(canvas, "AGGRESSION", 18f, 425f, 23f, true)
            txt(canvas, aggression?.let { "$it%" } ?: "--", 381f, 425f, 27f, true)
            val distanceText = if (targetVisible && targetDistanceM != null) {
                "DIST ~${String.format(Locale.US, "%.1f", targetDistanceM)}M"
            } else "DIST --"
            txt(canvas, distanceText, 18f, 453f, 16f)
            txt(canvas, if (profileReady) "HEIGHT ${profile.heightCm} CM" else "HEIGHT --",
                259f, 453f, 15f)
            txt(canvas, if (profileReady) "WEIGHT ${profile.massKg} KG" else "WEIGHT --",
                18f, 477f, 16f)
            txt(canvas, "BEARING ${azimuth.toString().padStart(3, '0')}",
                259f, 477f, 14f, false, 165)

            line(canvas, 18f, 493f, 462f, 493f, 145, 1f)
            txt(canvas, if (profileReady) "SEX ${profile.sex}" else "SEX --", 18f, 520f, 15f)
            txt(canvas, if (profileReady) "RACE ${profile.race}" else "RACE --",
                259f, 520f, 15f)
            txt(canvas, if (profileReady) "SECTOR ${profile.sector}" else "SECTOR --",
                18f, 545f, 15f)
            txt(canvas, if (profileReady) "DOB ${profile.birthDay}" else "DOB --",
                259f, 545f, 15f)
            txt(canvas, if (profileReady) "ACTIVITY ${profile.activity}" else "ACTIVITY --",
                18f, 570f, 15f)
            txt(canvas, if (profileReady) "FILE ${profile.code}" else "FILE --",
                259f, 570f, 15f)
            txt(canvas, if (profileReady) "BIRTH YEAR ${profile.birthYear}" else "BIRTH YEAR --",
                18f, 595f, 15f)
            txt(canvas, if (profileReady) "ADDR ${profile.address}" else "ADDR --",
                259f, 595f, 15f)
            line(canvas, 18f, 607f, 462f, 607f, 95, 1f)
            txt(canvas, "TAP 2X: MENU", 18f, 628f, 12f, false, 175)
            txt(canvas, cameraStatus.take(17), 125f, 628f, 10f, false, 130)
            txt(canvas, "ANALYSIS / STREAM", 248f, 628f, 13f, true)

            canvas.restore()
            postInvalidateDelayed(100L)
        }

        private fun diagnostics(c: Canvas, now: Long) {
            val elapsed = now - telemetryStartedAt
            if (now - lastTelemetryUpdateAt >= 350L) {
                lastTelemetryUpdateAt = now
                cpuValue = Random.nextInt(24, 68)
                procValue = Random.nextInt(3_800, 4_600)
                busValue = Random.nextInt(400, 680)
                cpuTail = Random.nextInt(100, 999)
                procTail = Random.nextInt(100, 999)
                busTail = Random.nextInt(100, 999)
                cpuSub = Random.nextInt(10, 99)
                procSub = Random.nextInt(10, 99)
                busSub = Random.nextInt(10, 99)
                addrHigh = Random.nextInt(0, 0x10000)
                addrLow = Random.nextInt(0, 0x10000)
                addrValue = Random.nextInt(0, 256)
            }
            val phase = elapsed % 5_000L
            val rowsVisible = if (phase < 300L) 0 else
                ((phase - 300L) / 230L + 1L).coerceAtMost(4L).toInt()
            val rows = arrayOf(
                "CPU  ${cpuValue.toString().padStart(2, '0')}% $cpuTail $cpuSub",
                "PROC ${procValue.toString().padStart(5, '0')} $procTail $procSub",
                "BUS  ${busValue.toString().padStart(5, '0')} $busTail $busSub",
                "ADD  ${addrHigh.toString(16).uppercase(Locale.US).padStart(4, '0')} " +
                    "${addrLow.toString(16).uppercase(Locale.US).padStart(4, '0')} " +
                    addrValue.toString(16).uppercase(Locale.US).padStart(2, '0')
            )
            for (index in 0 until rowsVisible) {
                txt(c, rows[index], 294f, 123f + index * 17f, 11f, false, 175)
            }
        }

        private fun reticle(c: Canvas, x: Float, y: Float, r: Float) {
            p.color = hudGreen
            p.alpha = 12
            p.style = Paint.Style.FILL
            c.drawCircle(x, y, r, p)
            p.style = Paint.Style.STROKE
            p.alpha = 118
            p.strokeWidth = 2.5f
            c.drawCircle(x, y, r, p)
            p.alpha = 78
            p.strokeWidth = 1.8f
            c.drawCircle(x, y, r * 0.7f, p)
            line(c, x - r * 0.7f, y, x + r * 0.7f, y, 93, 2f)
            line(c, x, y - r * 0.7f, x, y + r * 0.7f, 93, 2f)
            line(c, x - r - 14f, y, x - r, y, 128, 2f)
            line(c, x + r, y, x + r + 14f, y, 128, 2f)
            line(c, x, y - r - 14f, x, y - r, 128, 2f)
            line(c, x, y + r, x, y + r + 14f, 128, 2f)
            p.style = Paint.Style.FILL
            p.alpha = 128
            c.drawCircle(x, y, 3.5f, p)
        }

        private fun compass(c: Canvas, x: Float, y: Float, now: Long): Int {
            val elapsed = now - telemetryStartedAt
            val heading = if (sensedHeading != null && now - lastHeadingAt < 2_000L) {
                sensedHeading!!.toInt()
            } else {
                normalizeDegrees(166f + (3f * sin(elapsed / 4_000f))).toInt()
            }
            val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            for (index in directions.indices) {
                val angle = Math.toRadians(index * 45.0 - 90.0 + heading)
                val vx = cos(angle).toFloat()
                val vy = sin(angle).toFloat()
                line(c, x, y, x + vx * 40f, y + vy * 40f, 155, 1.5f)
                center(c, directions[index], x + vx * 53f, y + vy * 53f + 4f,
                    11f, index % 2 == 0)
            }
            p.color = hudGreen
            p.style = Paint.Style.STROKE
            p.alpha = 80
            p.strokeWidth = 1f
            c.drawCircle(x, y, 31f, p)
            c.drawCircle(x, y, 5f, p)
            val sweep = elapsed % 3_500L / 3_500.0 * Math.PI * 2.0
            line(c, x, y, x + cos(sweep).toFloat() * 31f,
                y + sin(sweep).toFloat() * 31f, 210, 1.8f)
            val blips = arrayOf(-17f to -11f, 21f to 8f, -4f to 23f)
            for (index in blips.indices) {
                val pulse = ((elapsed / 100L + index * 12L) % 40L).toFloat() / 40f
                if (pulse < 0.22f) {
                    p.color = hudGreen
                    p.alpha = (180f * (1f - pulse / 0.22f)).toInt()
                    p.style = Paint.Style.FILL
                    c.drawCircle(x + blips[index].first, y + blips[index].second, 3f, p)
                }
            }
            return heading
        }

        private fun txt(
            c: Canvas, s: String, x: Float, y: Float, size: Float,
            isBold: Boolean = false, opacity: Int = 230
        ) {
            p.color = hudGreen
            p.alpha = opacity
            p.style = Paint.Style.FILL
            p.typeface = if (isBold) bold else normal
            p.textSize = size
            c.drawText(s, x, y, p)
        }

        private fun center(c: Canvas, s: String, x: Float, y: Float, size: Float, isBold: Boolean) {
            p.typeface = if (isBold) bold else normal
            p.textSize = size
            txt(c, s, x - p.measureText(s) / 2f, y, size, isBold)
        }

        private fun line(
            c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float,
            opacity: Int, thickness: Float
        ) {
            p.color = hudGreen
            p.alpha = opacity
            p.style = Paint.Style.STROKE
            p.strokeWidth = thickness
            c.drawLine(x1, y1, x2, y2, p)
        }

        private fun batteryPercent(): Int {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        }
    }
}

package com.myra.assistant.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import androidx.core.content.ContextCompat

/**
 * Front-camera vision for MYRA. Captures a small JPEG frame every few seconds
 * and hands it to [onFrame], which MainViewModel forwards to the Live session
 * (same path as screen-share frames).
 *
 * Privacy design:
 * - OFF by default. The user enables it with "camera on karo"
 *   (set_camera_access tool) and disables with "camera band karo".
 * - Runs ONLY while a voice session is live (sessionLive flag from
 *   MainViewModel). Never in the background.
 * - Android shows the green camera dot whenever the camera is open —
 *   that is the OS privacy indicator and cannot be hidden.
 */
object CameraVision {

    /** Set by MainViewModel: receives base64 JPEG frames. */
    var onFrame: ((String) -> Unit)? = null

    /** True while a voice session is live. Managed by MainViewModel. */
    @Volatile
    var sessionLive = false

    private var camera: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var running = false
    private var lastSentAt = 0L

    private const val PREFS = "myra_camera"
    private const val FRAME_MS = 4000L // one frame every 4 seconds

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("enabled", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", on).apply()
        refresh(ctx)
    }

    /** (Re)evaluate whether the camera should be open right now. */
    fun refresh(ctx: Context) {
        val want = sessionLive && isEnabled(ctx) && hasPermission(ctx)
        if (want && !running) start(ctx.applicationContext)
        else if (!want && running) stop()
    }

    private fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun start(ctx: Context) {
        if (running) return
        if (!hasPermission(ctx)) return
        running = true
        try {
            thread = HandlerThread("myra-camera").also { it.start() }
            bgHandler = Handler(thread!!.looper)
            val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = frontCameraId(manager) ?: run { stop(); return }
            reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2).also { r ->
                r.setOnImageAvailableListener({ rd ->
                    val img = try {
                        rd.acquireLatestImage()
                    } catch (_: Exception) {
                        null
                    }
                    if (img != null) {
                        try {
                            val now = System.currentTimeMillis()
                            if (now - lastSentAt >= FRAME_MS) {
                                lastSentAt = now
                                val buf = img.planes[0].buffer
                                val bytes = ByteArray(buf.remaining())
                                buf.get(bytes)
                                onFrame?.invoke(
                                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                                )
                            }
                        } catch (_: Exception) {
                        } finally {
                            img.close()
                        }
                    }
                }, bgHandler)
            }
            manager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    camera = c
                    try {
                        c.createCaptureSession(
                            listOf(reader!!.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    captureSession = s
                                    try {
                                        val req = c.createCaptureRequest(
                                            CameraDevice.TEMPLATE_PREVIEW
                                        )
                                        req.addTarget(reader!!.surface)
                                        s.setRepeatingRequest(req.build(), null, bgHandler)
                                    } catch (_: Exception) {
                                        stop()
                                    }
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    stop()
                                }
                            },
                            bgHandler
                        )
                    } catch (_: Exception) {
                        stop()
                    }
                }

                override fun onDisconnected(c: CameraDevice) {
                    stop()
                }

                override fun onError(c: CameraDevice, error: Int) {
                    stop()
                }
            }, bgHandler)
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop() {
        running = false
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        try {
            camera?.close()
        } catch (_: Exception) {
        }
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        try {
            thread?.quitSafely()
        } catch (_: Exception) {
        }
        captureSession = null
        camera = null
        reader = null
        thread = null
        bgHandler = null
    }

    private fun frontCameraId(manager: CameraManager): String? {
        return try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }
}

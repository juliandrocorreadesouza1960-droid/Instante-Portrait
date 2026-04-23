package com.instantportrait.exif

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewTreeLifecycleOwner
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Preview + ImageAnalysis no mesmo CameraX: detecta mudança na cena (tripé parado).
 * Ao disparar, grava JPEG temporário em cache e envia [onScenePhoto] com file:// URI
 * para o JS reutilizar saveJpegToGalleryAsync + filtragem.
 */
@SuppressLint("ViewConstructor")
class SceneMotionCameraView(
  context: Context,
  appContext: AppContext
) : ExpoView(context, appContext) {

  /**
   * Garante medida/layout Android para crianças (p.ex. [PreviewView]) alinhada ao tamanho Yoga
   * — necessário em várias combinações RN+CameraX, senão a área pode ficar 0×0 ou o preview vazio.
   */
  override val shouldUseAndroidLayout: Boolean = true

  private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

  private val onScenePhoto by EventDispatcher<Map<String, Any>>()
  private val onSceneReady by EventDispatcher<Map<String, Any>>()
  private val onSceneError by EventDispatcher<Map<String, Any>>()

  private val previewView = PreviewView(context).also { pv ->
    pv.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE)
    pv.layoutParams = LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT
    )
    addView(pv)
  }

  private var cameraProvider: ProcessCameraProvider? = null
  private var imageCapture: ImageCapture? = null
  private var bound = false
  private var bindOwnerRetryCount = 0

  @Volatile
  private var snapshotExposureNs: Long = readSnapshotExposureNs(context)

  @Volatile
  private var snapshotIso: Int = readSnapshotIso(context)

  @Volatile
  private var autoExposureEnabled: Boolean = readAutoExposureEnabled(context)

  @Volatile
  private var bindingInFlight: Boolean = false

  @Volatile
  private var rebindScheduled: Boolean = false

  @Volatile
  private var analysisActive = false

  @Volatile
  private var sensitivityLevel = 2 // 1=menos sensível, 3=mais

  private var lastGrid: IntArray? = null
  private var lastCaptureAt = 0L

  @Volatile
  private var lastMeanLuma: Int = 255

  @Volatile
  private var darkPreviewStreak: Int = 0

  @Volatile
  private var snapshotInFlight = false

  /** Pausa mínima entre dois disparos (ms), configurável no JS (ex.: 500–2000). */
  @Volatile
  private var minCaptureIntervalMs: Long = 1000L

  private val analyzer = ImageAnalysis.Analyzer { image ->
    try {
      val score = computeMeanLumaDiff(image)
      if (score < 0f) return@Analyzer

      // Se o preview estiver preto (subexposição severa) com exposição manual,
      // troca para auto-exposição e rebind, sem depender de disparo/foto.
      if (!autoExposureEnabled) {
        if (lastMeanLuma <= 10) {
          darkPreviewStreak++
        } else {
          darkPreviewStreak = 0
        }
        if (darkPreviewStreak >= 8) {
          darkPreviewStreak = 0
          autoExposureEnabled = true
          writeAutoExposureEnabled(context, true)
          scheduleRebind()
          return@Analyzer
        }
      }

      if (!analysisActive) return@Analyzer

      val th = when (sensitivityLevel) {
        1 -> 6.5f
        3 -> 2.2f
        else -> 3.8f
      }
      if (score < th) return@Analyzer

      val now = System.currentTimeMillis()
      val gap = synchronized(this@SceneMotionCameraView) { minCaptureIntervalMs }
      if (now - lastCaptureAt < gap) return@Analyzer
      lastCaptureAt = now

      post {
        if (!snapshotInFlight) takeSnapshot()
      }
    } catch (e: Throwable) {
      Log.e(TAG, "analyze", e)
    } finally {
      image.close()
    }
  }

  private fun computeMeanLumaDiff(image: ImageProxy): Float {
    val plane = image.planes[0]
    val buf = plane.buffer.duplicate()
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val w = image.width
    val h = image.height
    val step = 12
    val gw = (w + step - 1) / step
    val gh = (h + step - 1) / step
    val grid = IntArray(gw * gh)
    var gi = 0
    var meanSum = 0L
    var yy = 0
    while (yy < h) {
      var xx = 0
      while (xx < w) {
        val x = xx.coerceAtMost(w - 1)
        val y = yy.coerceAtMost(h - 1)
        val off = y * rowStride + x * pixelStride
        val v = buf.get(off).toInt() and 0xFF
        grid[gi++] = v
        meanSum += v.toLong()
        xx += step
      }
      yy += step
    }

    lastMeanLuma = if (gi <= 0) 255 else (meanSum / gi.toLong()).toInt()

    val prev = lastGrid
    lastGrid = grid.copyOf()
    if (prev == null || prev.size != grid.size) return -1f

    var sum = 0L
    for (i in grid.indices) {
      sum += abs(grid[i] - prev[i])
    }
    return sum.toFloat() / grid.size
  }

  private fun takeSnapshot() {
    val ic = imageCapture ?: return
    val reactCtx = appContext.reactContext ?: return
    if (!analysisActive) return
    if (snapshotInFlight) return
    snapshotInFlight = true

    val outFile = File(reactCtx.cacheDir, "scene_motion_${System.currentTimeMillis()}.jpg")
    val opts = ImageCapture.OutputFileOptions.Builder(outFile).build()
    ic.takePicture(
      opts,
      ContextCompat.getMainExecutor(context),
      object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
          snapshotInFlight = false
          // Fallback automático: se a exposição manual gerar um JPEG praticamente preto,
          // troca para auto-exposição (AE ON) e rebind.
          if (!autoExposureEnabled) {
            val mean = runCatching { estimateMeanLumaFromJpeg(outFile.absolutePath) }.getOrDefault(255)
            if (mean in 0..18) {
              autoExposureEnabled = true
              writeAutoExposureEnabled(context, true)
              onSceneError(mapOf("message" to "autoExposureFallback"))
              scheduleRebind()
            }
          }
          val uriStr = Uri.fromFile(outFile).toString()
          onScenePhoto(mapOf("uri" to uriStr))
        }

        override fun onError(exc: ImageCaptureException) {
          snapshotInFlight = false
          Log.e(TAG, "takePicture", exc)
          onSceneError(mapOf("message" to (exc.message ?: "ImageCapture")))
        }
      }
    )
  }

  fun setSensitivity(level: Int) {
    sensitivityLevel = level.coerceIn(1, 3)
    lastGrid = null
  }

  fun setMinCaptureIntervalMs(ms: Int) {
    val v = ms.toLong().coerceIn(300L, 10_000L)
    synchronized(this) {
      minCaptureIntervalMs = v
    }
  }

  fun setSnapshotExposureNs(ns: Long) {
    val clamped = ns.coerceIn(100_000L, 100_000_000L) // ~1/10_000s .. ~1/10s
    if (snapshotExposureNs == clamped) return
    snapshotExposureNs = clamped
    writeSnapshotExposureNs(context, clamped)
    scheduleRebind()
  }

  fun setSnapshotIso(iso: Int) {
    val clamped = iso.coerceIn(100, 6400)
    if (snapshotIso == clamped) return
    snapshotIso = clamped
    writeSnapshotIso(context, clamped)
    scheduleRebind()
  }

  private fun scheduleRebind() {
    if (rebindScheduled) return
    rebindScheduled = true
    postDelayed(
      {
        rebindScheduled = false
        cleanupCamera()
        bindCameraIfNeeded()
      },
      150L
    )
  }

  fun setAnalysisActive(active: Boolean) {
    analysisActive = active
    if (!active) {
      lastGrid = null
    }
  }

  fun cleanupCamera() {
    analysisActive = false
    lastGrid = null
    snapshotInFlight = false
    bindOwnerRetryCount = 0
    bindingInFlight = false
    synchronized(this) {
      runCatching { cameraProvider?.unbindAll() }
      bound = false
      cameraProvider = null
      imageCapture = null
    }
  }

  /**
   * RN/Expo nem sempre anexa [ViewTreeLifecycleOwner] às views nativas a tempo. CameraX exige
   * um [LifecycleOwner] estável: usamos a [Activity] atual (como o expo-camera) em último fallback.
   */
  private fun lifecycleOwner(): LifecycleOwner? {
    ViewTreeLifecycleOwner.get(this)?.let { return it }
    (parent as? ViewGroup)?.let { ViewTreeLifecycleOwner.get(it) }?.let { return it }
    return appContext.currentActivity as? LifecycleOwner
  }

  private fun bindCameraIfNeeded() {
    synchronized(this) {
      if (bound) return
      if (bindingInFlight) return
      if (width < 2 || height < 2) return
    }
    val owner = lifecycleOwner() ?: run {
      if (bindOwnerRetryCount < 40) {
        bindOwnerRetryCount++
        post { bindCameraIfNeeded() }
      } else {
        Log.e(TAG, "bindCamera: no LifecycleOwner after max retries")
        onSceneError(mapOf("message" to "noLifecycleOwner"))
      }
      return
    }
    bindOwnerRetryCount = 0

    val future = ProcessCameraProvider.getInstance(context)
    future.addListener(
      {
        try {
          synchronized(this@SceneMotionCameraView) {
            if (bound) return@addListener
          }
          bindingInFlight = true
          val provider = future.get()
          cameraProvider = provider

          val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
          }

          val analysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
          analysis.setAnalyzer(cameraExecutor, analyzer)

          imageCapture = buildSnapshotImageCapture()

          provider.unbindAll()
          provider.bindToLifecycle(
            owner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
            imageCapture!!
          )
          synchronized(this@SceneMotionCameraView) {
            bound = true
          }
          bindingInFlight = false
          onSceneReady(mapOf("ok" to true))
        } catch (e: Exception) {
          bindingInFlight = false
          Log.e(TAG, "bindCamera", e)
          onSceneError(mapOf("message" to (e.message ?: "bindCamera")))
        }
      },
      ContextCompat.getMainExecutor(context)
    )
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    post {
      bindCameraIfNeeded()
    }
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    super.onLayout(changed, l, t, r, b)
    val w = r - l
    val h = b - t
    previewView.layout(0, 0, w, h)
    if (!bound && w > 2 && h > 2) {
      post { bindCameraIfNeeded() }
    }
  }

  override fun onDetachedFromWindow() {
    cleanupCamera()
    super.onDetachedFromWindow()
  }

  /** [ImageCapture] com obturador curto (exposição manual) para congelar o movimento. */
  @OptIn(ExperimentalCamera2Interop::class)
  private fun buildSnapshotImageCapture(): ImageCapture {
    val b = ImageCapture.Builder()
      .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
      .setJpegQuality(92)
    val ext = Camera2Interop.Extender(b)
    if (autoExposureEnabled) {
      ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
    } else {
      ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
      ext.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, snapshotExposureNs)
      ext.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, snapshotIso)
      val frameNs = (snapshotExposureNs * 2).coerceAtLeast(1_000_000L)
      ext.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
    }
    return b.build()
  }

  companion object {
    private const val TAG = "SceneMotionCameraView"

    private const val PREFS = "instant_portrait_prefs"
    private const val KEY_EXPOSURE_NS = "snapshotExposureNs"
    private const val KEY_ISO = "snapshotIso"
    private const val KEY_AUTO_EXPOSURE = "snapshotAutoExposure"
    private const val DEFAULT_EXPOSURE_NS: Long = 1_000_000_000L / 1000 // 1/1000s
    private const val DEFAULT_ISO: Int = 1800

    private fun prefs(ctx: Context) =
      ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun readSnapshotExposureNs(ctx: Context): Long {
      val v = runCatching { prefs(ctx).getLong(KEY_EXPOSURE_NS, DEFAULT_EXPOSURE_NS) }
        .getOrDefault(DEFAULT_EXPOSURE_NS)
      return v.coerceIn(100_000L, 100_000_000L)
    }

    fun writeSnapshotExposureNs(ctx: Context, ns: Long) {
      runCatching {
        prefs(ctx).edit().putLong(KEY_EXPOSURE_NS, ns).apply()
      }
    }

    fun readSnapshotIso(ctx: Context): Int {
      val v = runCatching { prefs(ctx).getInt(KEY_ISO, DEFAULT_ISO) }.getOrDefault(DEFAULT_ISO)
      return v.coerceIn(100, 6400)
    }

    fun writeSnapshotIso(ctx: Context, iso: Int) {
      runCatching {
        prefs(ctx).edit().putInt(KEY_ISO, iso.coerceIn(100, 6400)).apply()
      }
    }

    fun readAutoExposureEnabled(ctx: Context): Boolean {
      return runCatching { prefs(ctx).getBoolean(KEY_AUTO_EXPOSURE, false) }.getOrDefault(false)
    }

    fun writeAutoExposureEnabled(ctx: Context, enabled: Boolean) {
      runCatching { prefs(ctx).edit().putBoolean(KEY_AUTO_EXPOSURE, enabled).apply() }
    }

    private fun estimateMeanLumaFromJpeg(path: String): Int {
      val opts = BitmapFactory.Options().apply {
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        inSampleSize = 16
      }
      val bmp = BitmapFactory.decodeFile(path, opts) ?: return 255
      val w = bmp.width.coerceAtLeast(1)
      val h = bmp.height.coerceAtLeast(1)
      val stepX = (w / 24).coerceAtLeast(1)
      val stepY = (h / 24).coerceAtLeast(1)
      var sum = 0L
      var count = 0L
      var y = 0
      while (y < h) {
        var x = 0
        while (x < w) {
          val c = bmp.getPixel(x, y)
          val r = (c shr 16) and 0xFF
          val g = (c shr 8) and 0xFF
          val b = c and 0xFF
          sum += (r + g + b) / 3
          count++
          x += stepX
        }
        y += stepY
      }
      bmp.recycle()
      return if (count <= 0L) 255 else (sum / count).toInt()
    }
  }
}

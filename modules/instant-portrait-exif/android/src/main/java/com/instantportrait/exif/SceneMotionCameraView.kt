package com.instantportrait.exif

import android.annotation.SuppressLint
import android.content.Context
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
  private var analysisActive = false

  @Volatile
  private var sensitivityLevel = 2 // 1=menos sensível, 3=mais

  private var lastGrid: IntArray? = null
  private var lastCaptureAt = 0L

  @Volatile
  private var snapshotInFlight = false

  /** Pausa mínima entre dois disparos (ms), configurável no JS (ex.: 500–2000). */
  @Volatile
  private var minCaptureIntervalMs: Long = 1000L

  private val analyzer = ImageAnalysis.Analyzer { image ->
    try {
      if (!analysisActive) return@Analyzer
      val score = computeMeanLumaDiff(image)
      if (score < 0f) return@Analyzer

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
    var yy = 0
    while (yy < h) {
      var xx = 0
      while (xx < w) {
        val x = xx.coerceAtMost(w - 1)
        val y = yy.coerceAtMost(h - 1)
        val off = y * rowStride + x * pixelStride
        grid[gi++] = buf.get(off).toInt() and 0xFF
        xx += step
      }
      yy += step
    }

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
          onSceneReady(mapOf("ok" to true))
        } catch (e: Exception) {
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

  /**
   * [ImageCapture] com obturador ~1/1700 s (exposição manual) para congelar o movimento e suavizar trepidação.
   */
  @OptIn(ExperimentalCamera2Interop::class)
  private fun buildSnapshotImageCapture(): ImageCapture {
    val b = ImageCapture.Builder()
      .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
      .setJpegQuality(92)
    val ext = Camera2Interop.Extender(b)
    ext.setCaptureRequestOption(
      CaptureRequest.CONTROL_AE_MODE,
      CaptureRequest.CONTROL_AE_MODE_OFF
    )
    ext.setCaptureRequestOption(
      CaptureRequest.SENSOR_EXPOSURE_TIME,
      SNAPSHOT_EXPOSURE_NS
    )
    ext.setCaptureRequestOption(
      CaptureRequest.SENSOR_SENSITIVITY,
      SNAPSHOT_ISO
    )
    val frameNs = (SNAPSHOT_EXPOSURE_NS * 2).coerceAtLeast(1_000_000L)
    ext.setCaptureRequestOption(
      CaptureRequest.SENSOR_FRAME_DURATION,
      frameNs
    )
    return b.build()
  }

  companion object {
    private const val TAG = "SceneMotionCameraView"

    /**
     * ~1/1700 s (1e9/1700 ns). Cenas muito escuras exigem ISO mais alto noutro ajuste futuro.
     */
    private const val SNAPSHOT_EXPOSURE_NS: Long = 1_000_000_000L / 1700

    private const val SNAPSHOT_ISO: Int = 1000
  }
}

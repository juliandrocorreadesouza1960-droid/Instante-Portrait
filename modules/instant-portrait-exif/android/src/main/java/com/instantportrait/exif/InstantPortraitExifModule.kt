package com.instantportrait.exif

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Mínimo exigido pelo portal (print do cliente) + teto de ficheiro; aplicado no nativo
 * mesmo se o mapa JS não chegar (release / bridge).
 */
private const val PORTAL_MIN_W = 3450
private const val PORTAL_MIN_H = 2300
private const val MAX_JPEG_FILE_BYTES = 12 * 1024 * 1024
private const val DECODE_MAX_LONG_SIDE = 10000

class InstantPortraitExifModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("InstantPortraitExif")

    // Ping trivial para manter o bridge/JVM ativo entre disparos do expo-camera
    // (evita o bug em que o takePictureAsync fica 10-25s em idle sem outra atividade).
    AsyncFunction("pingAsync") {
      return@AsyncFunction System.currentTimeMillis()
    }

    // Abre a pasta do armazenamento (DocumentProvider) no app de ficheiros / galeria.
    // [which] "kept" -> DCIM/Instant Portrait, "rejected" -> .../Rejected
    AsyncFunction("openInstantPortraitFolderAsync") { which: String? ->
      val act = appContext.currentActivity
        ?: return@AsyncFunction mapOf("ok" to false, "error" to "no_activity")
      val documentId = when (which) {
        "rejected" -> "primary:DCIM/Instant Portrait/Rejected"
        else -> "primary:DCIM/Instant Portrait"
      }
      return@AsyncFunction runCatching {
        val uri = DocumentsContract.buildDocumentUri(
          "com.android.externalstorage.documents",
          documentId
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(uri, "vnd.android.document/directory")
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        act.startActivity(
          Intent.createChooser(intent, "Galeria — Instant Portrait").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
        )
        mapOf("ok" to true, "error" to "")
      }.getOrElse { e ->
        // Fallback: tenta abrir a pasta sem MIME (alguns OEMs).
        runCatching {
          val uri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            documentId
          )
          val i2 = Intent(Intent.ACTION_VIEW).apply {
            data = uri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
          act.startActivity(
            Intent.createChooser(i2, "Galeria — Instant Portrait").apply {
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
          )
          mapOf("ok" to true, "error" to "")
        }.getOrElse { e2 ->
          mapOf("ok" to false, "error" to (e2.message ?: e.message ?: "open"))
        }
      }
    }

    // Deleta um arquivo local (typicamente o cache do expo-camera depois de salvar).
    // Evita acúmulo no /cache que degrada IO ao longo de uma sessão contínua.
    AsyncFunction("deleteFileAsync") { uri: String ->
      val path = when {
        uri.startsWith("file://") -> Uri.parse(uri).path
        uri.startsWith("/") -> uri
        else -> null
      } ?: return@AsyncFunction false
      val f = File(path)
      return@AsyncFunction runCatching { f.delete() }.getOrDefault(false)
    }

    AsyncFunction("writeExifAsync") { uri: String, data: Map<String, Any?> ->
      val path = resolvePath(uri)
        ?: throw IllegalArgumentException("Arquivo n\u00e3o encontrado para URI: $uri")

      val exif = ExifInterface(path)

      // Data/hora (DateTimeOriginal, DateTimeDigitized, DateTime) no formato EXIF exigido
      val nowMs = (data["timestampMs"] as? Number)?.toLong() ?: System.currentTimeMillis()

      val exifDtFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
      val exifDate = exifDtFormat.format(Date(nowMs))

      exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
      exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifDate)
      exif.setAttribute(ExifInterface.TAG_DATETIME, exifDate)

      // Data/hora com offset (ex.: -03:00)
      val offsetMinutes = TimeZone.getDefault().getOffset(nowMs) / 60_000
      val sign = if (offsetMinutes >= 0) "+" else "-"
      val absMin = kotlin.math.abs(offsetMinutes)
      val hh = absMin / 60
      val mm = absMin % 60
      val offsetStr = String.format(Locale.US, "%s%02d:%02d", sign, hh, mm)
      // Em versões mais novas do ExifInterface existem TAG_OFFSET_TIME_*; como é condicional,
      // setamos via string literal para não depender da constante em tempo de compilação.
      exif.setAttribute("OffsetTime", offsetStr)
      exif.setAttribute("OffsetTimeOriginal", offsetStr)
      exif.setAttribute("OffsetTimeDigitized", offsetStr)

      // GPS (quando fornecido)
      val lat = (data["latitude"] as? Number)?.toDouble()
      val lng = (data["longitude"] as? Number)?.toDouble()
      if (lat != null && lng != null) {
        exif.setLatLong(lat, lng)
      }

      val altitude = (data["altitude"] as? Number)?.toDouble()
      if (altitude != null) {
        exif.setAltitude(altitude)
      }

      exif.saveAttributes()

      return@AsyncFunction mapOf(
        "path" to path,
        "dateTime" to exifDate,
        "offset" to offsetStr,
        "hasGps" to (lat != null && lng != null)
      )
    }

    // Salva no MediaStore (DCIM/Instant Portrait) criando um novo item.
    // Isso evita o prompt repetitivo do Samsung "permitir modificar esta foto?".
    // Pode: encolher se maxSidePx > 0; ampliar (preservando proporção) se a imagem for menor
    // que minOutputWidthPx / minOutputHeightPx (p.ex. portais 3450×2300), preservando
    // TAG_ORIENTATION do JPEG original após reencode.
    AsyncFunction("saveJpegToGalleryAsync") { uri: String, data: Map<String, Any?> ->
      val reactContext = appContext.reactContext ?: throw IllegalStateException("Sem reactContext")
      val cr = reactContext.contentResolver

      val srcPath = resolvePath(uri)
        ?: throw IllegalArgumentException("Arquivo n\u00e3o encontrado para URI: $uri")

      val nowMs = (data["timestampMs"] as? Number)?.toLong() ?: System.currentTimeMillis()
      val fileName = (data["fileName"] as? String)
        ?: ("InstantPortrait_" + nowMs.toString() + ".jpg")
      val maxSidePx = (data["maxSidePx"] as? Number)?.toInt() ?: 0
      var minOutW = (data["minOutputWidthPx"] as? Number)?.toInt() ?: 0
      var minOutH = (data["minOutputHeightPx"] as? Number)?.toInt() ?: 0
      if (minOutW <= 0) minOutW = PORTAL_MIN_W
      if (minOutH <= 0) minOutH = PORTAL_MIN_H

      val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Instant Portrait")
        put(MediaStore.Images.Media.IS_PENDING, 1)
      }

      val destUri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Falha ao inserir no MediaStore")

      try {
        val fSrc = File(srcPath)
        val (lw, lh) = this@InstantPortraitExifModule.logicalJpegSize(srcPath)
        if (lw <= 0 || lh <= 0) {
          throw IllegalStateException("Imagem com dimens\u00e3o inv\u00e1lida")
        }
        val exifPre = ExifInterface(srcPath)
        val oPre = exifPre.getAttributeInt(
          ExifInterface.TAG_ORIENTATION,
          ExifInterface.ORIENTATION_NORMAL
        )
        val exifUpright = oPre == ExifInterface.ORIENTATION_NORMAL ||
          oPre == ExifInterface.ORIENTATION_UNDEFINED

        val needUp = lw < minOutW || lh < minOutH
        val needDown = maxSidePx > 0 && max(lw, lh) > maxSidePx
        var needReencode = needUp || needDown || !exifUpright ||
          (fSrc.exists() && fSrc.length() > MAX_JPEG_FILE_BYTES)

        if (!needReencode) {
          cr.openOutputStream(destUri, "w")?.use { out ->
            FileInputStream(fSrc).use { input -> input.copyTo(out) }
          } ?: throw IllegalStateException("Falha ao abrir outputStream do MediaStore")
        } else {
          var bmp = this@InstantPortraitExifModule.decodeBitmapUpright(
            path = srcPath,
            longSideCeil = DECODE_MAX_LONG_SIDE
          ) ?: throw IllegalStateException("Falha ao decodificar o JPEG em pixels")

          bmp = this@InstantPortraitExifModule.scaleToAtLeast(bmp, minOutW, minOutH)
          if (maxSidePx > 0) {
            val m = max(bmp.width, bmp.height)
            if (m > maxSidePx) {
              val s = maxSidePx.toFloat() / m
              val nw = (bmp.width * s).toInt().coerceAtLeast(1)
              val nh = (bmp.height * s).toInt().coerceAtLeast(1)
              if (min(nw, nh) < min(minOutW, minOutH) || (nw < minOutW) || (nh < minOutH)) {
                // n\u00e3o encolher a ponto de violar m\u00ednimos; ignora tecto
              } else {
                val d = Bitmap.createScaledBitmap(bmp, nw, nh, true)
                if (d != bmp) {
                  bmp.recycle()
                  bmp = d
                }
              }
            }
          }

          val jpg = this@InstantPortraitExifModule.encodeJpegWithSizeCap(
            bmp, MAX_JPEG_FILE_BYTES, minOutW, minOutH
          )
          bmp.recycle()

          cr.openOutputStream(destUri, "w")?.use { out ->
            out.write(jpg)
          } ?: throw IllegalStateException("Falha ao abrir outputStream do MediaStore")
        }

        // EXIF: ap\u00f3s reencode com pixels "em pé", ORIENTATION=1; GPS/data como antes.
        cr.openFileDescriptor(destUri, "rw")?.use { pfd ->
          val exif = ExifInterface(pfd.fileDescriptor)
          applyExif(exif, nowMs, data)
          if (needReencode) {
            exif.setAttribute(
              ExifInterface.TAG_ORIENTATION,
              ExifInterface.ORIENTATION_NORMAL.toString()
            )
          }
          exif.saveAttributes()
        }

        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }.also {
          cr.update(destUri, it, null, null)
        }

        return@AsyncFunction mapOf(
          "uri" to destUri.toString(),
          "fileName" to fileName,
          "resized" to needReencode
        )
      } catch (t: Throwable) {
        runCatching { cr.delete(destUri, null, null) }
        throw t
      }
    }

    // Analisa a imagem salva (content://) e retorna:
    // - faces: quantidade de rostos detectados (proxy para "tem pessoas")
    // - blurScore: variância do Laplaciano (quanto maior, mais nítida)
    AsyncFunction("analyzeImageAsync") { contentUri: String ->
      val reactContext = appContext.reactContext ?: throw IllegalStateException("Sem reactContext")
      val cr = reactContext.contentResolver
      val uri = Uri.parse(contentUri)

      // Blur score em bitmap reduzido (rápido)
      val blurScore = cr.openInputStream(uri)?.use { input ->
        val bmp = android.graphics.BitmapFactory.decodeStream(input)
          ?: return@use 0.0
        val small = android.graphics.Bitmap.createScaledBitmap(
          bmp,
          max(64, bmp.width / 4),
          max(64, bmp.height / 4),
          true
        )
        if (small != bmp) bmp.recycle()
        val score = laplacianVariance(small)
        small.recycle()
        score
      } ?: 0.0

      // Faces via ML Kit (sync via latch)
      val image = InputImage.fromFilePath(reactContext, uri)
      val options = FaceDetectorOptions.Builder()
        // Mais tolerante para rostos parcialmente visíveis (ex.: de lado / cortado)
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setMinFaceSize(0.04f)
        .build()
      val detector = FaceDetection.getClient(options)

      var facesCount = 0
      var facesError: Throwable? = null
      val latch = CountDownLatch(1)
      detector.process(image)
        .addOnSuccessListener { faces ->
          facesCount = faces.size
          latch.countDown()
        }
        .addOnFailureListener { e ->
          facesError = e
          latch.countDown()
        }
      latch.await()
      detector.close()
      facesError?.let { /* best effort: ignora */ }

      // Pessoa/corpo via Pose (melhor para "de costas", corpo parcial, rosto cortado).
      // SINGLE_IMAGE_MODE é mais preciso para fotos isoladas que STREAM_MODE (este é
      // pensado pra sequência de frames de vídeo).
      val poseOptions = PoseDetectorOptions.Builder()
        .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
        .build()
      val poseDetector = PoseDetection.getClient(poseOptions)
      var confidentLandmarks = 0
      var maxLikelihood = 0.0f
      var poseError: Throwable? = null
      val poseLatch = CountDownLatch(1)
      poseDetector.process(image)
        .addOnSuccessListener { pose ->
          val landmarks = pose.allPoseLandmarks
          confidentLandmarks = landmarks.count { it.inFrameLikelihood >= 0.5f }
          maxLikelihood = landmarks.maxOfOrNull { it.inFrameLikelihood } ?: 0.0f
          poseLatch.countDown()
        }
        .addOnFailureListener { e ->
          poseError = e
          poseLatch.countDown()
        }
      poseLatch.await()
      poseDetector.close()
      poseError?.let { /* best effort */ }

      // Regra final de "tem pessoa":
      //  - Se detectou algum rosto, tem pessoa. OU
      //  - Se pelo menos 3 landmarks de pose estão acima de 50% de confiança (pessoa parcial/de costas). OU
      //  - Se qualquer landmark passou de 80% de confiança (pessoa clara mesmo que só cabeça/ombros).
      val hasPerson = facesCount > 0 ||
        confidentLandmarks >= 3 ||
        maxLikelihood >= 0.8f

      return@AsyncFunction mapOf(
        "faces" to facesCount,
        "hasPerson" to hasPerson,
        "confidentLandmarks" to confidentLandmarks,
        "maxPoseLikelihood" to maxLikelihood.toDouble(),
        "blurScore" to blurScore
      )
    }

    // Move um item do MediaStore alterando o RELATIVE_PATH (Android 10+).
    // Ex.: targetRelativePath = "DCIM/Instant Portrait/Rejected"
    AsyncFunction("moveInGalleryAsync") { contentUri: String, targetRelativePath: String ->
      val reactContext = appContext.reactContext ?: throw IllegalStateException("Sem reactContext")
      val cr = reactContext.contentResolver
      val uri = Uri.parse(contentUri)

      val values = ContentValues().apply {
        put(MediaStore.Images.Media.RELATIVE_PATH, targetRelativePath)
      }
      val updated = cr.update(uri, values, null, null)
      return@AsyncFunction mapOf("updated" to updated)
    }
  }

  /** Dimens\u00e3o vis\u00edvel (galeria / validadores), respeitando rota\u00e7\u00e3o EXIF. */
  private fun logicalJpegSize(path: String): Pair<Int, Int> {
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, b)
    var w = b.outWidth
    var h = b.outHeight
    if (w <= 0 || h <= 0) return Pair(0, 0)
    val e = ExifInterface(path)
    when (
      e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
      ExifInterface.ORIENTATION_ROTATE_90,
      ExifInterface.ORIENTATION_TRANSPOSE,
      ExifInterface.ORIENTATION_ROTATE_270,
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        val t = w; w = h; h = t
      }
    }
    return Pair(w, h)
  }

  /** Carrega o JPEG, aplica a rota\u00e7\u00e3o do EXIF para a bitmap \u2018de p\u00e9\u2019. */
  private fun decodeBitmapUpright(path: String, longSideCeil: Int): Bitmap? {
    val b0 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, b0)
    var w = b0.outWidth
    var h = b0.outHeight
    if (w <= 0 || h <= 0) return null
    var inSample = 1
    while (max(w, h) / inSample > longSideCeil) {
      inSample *= 2
    }
    val opts = BitmapFactory.Options().apply {
      inSampleSize = inSample
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    var bmp = BitmapFactory.decodeFile(path, opts) ?: return null
    val e = ExifInterface(path)
    val matrix = Matrix()
    when (e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    }
    if (matrix.isIdentity) return bmp
    val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    if (r != bmp) bmp.recycle()
    return r
  }

  /** Escala para ambos lados cumprirem o m\u00edn. do portal (p.ex. 3450x2300). */
  private fun scaleToAtLeast(b: Bitmap, minW: Int, minH: Int): Bitmap {
    if (b.width >= minW && b.height >= minH) {
      return b
    }
    val s = max(
      if (minW > 0) minW * 1f / b.width else 0f,
      if (minH > 0) minH * 1f / b.height else 0f
    )
    if (s <= 1.001f) {
      return b
    }
    val nw = max(1, ceil(b.width * s.toDouble()).toInt())
    val nh = max(1, ceil(b.height * s.toDouble()).toInt())
    val o = Bitmap.createScaledBitmap(b, nw, nh, true)
    if (o != b) b.recycle()
    return o
  }

  /**
   * Gera bytes JPEG, tentando n\u00e3o exceder [maxBytes] (12MB no portal) sem violar
   * o m\u00ednimo de p\u00edxeis; n\u00e3o d\u00e1 recycle() em [bIn] (quem chama trata).
   */
  private fun encodeJpegWithSizeCap(
    bIn: Bitmap,
    maxBytes: Int,
    minW: Int,
    minH: Int
  ): ByteArray {
    var cur = bIn
    for (round in 0..24) {
      for (q in intArrayOf(90, 85, 80, 75, 70, 65, 60, 55, 50, 45, 40)) {
        val ba = ByteArrayOutputStream()
        cur.compress(Bitmap.CompressFormat.JPEG, q, ba)
        if (ba.size() <= maxBytes) {
          if (cur != bIn) {
            cur.recycle()
          }
          return ba.toByteArray()
        }
      }
      if (cur.width * 0.9f < minW || cur.height * 0.9f < minH) {
        val o = ByteArrayOutputStream()
        cur.compress(Bitmap.CompressFormat.JPEG, 40, o)
        if (cur != bIn) {
          cur.recycle()
        }
        return o.toByteArray()
      }
      val nww = (cur.width * 0.9f).toInt().coerceAtLeast(minW)
      val nhh = (cur.height * 0.9f).toInt().coerceAtLeast(minH)
      val s2 = Bitmap.createScaledBitmap(cur, nww, nhh, true)
      if (s2 != cur) {
        if (cur != bIn) {
          cur.recycle()
        }
        cur = s2
      } else {
        val o = ByteArrayOutputStream()
        cur.compress(Bitmap.CompressFormat.JPEG, 40, o)
        if (cur != bIn) {
          cur.recycle()
        }
        return o.toByteArray()
      }
    }
    return ByteArrayOutputStream().apply {
      cur.compress(Bitmap.CompressFormat.JPEG, 40, this)
    }.toByteArray().also {
      if (cur != bIn) {
        cur.recycle()
      }
    }
  }

  private fun applyExif(exif: ExifInterface, nowMs: Long, data: Map<String, Any?>) {
    val exifDtFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
    val exifDate = exifDtFormat.format(Date(nowMs))

    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
    exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifDate)
    exif.setAttribute(ExifInterface.TAG_DATETIME, exifDate)

    val offsetMinutes = TimeZone.getDefault().getOffset(nowMs) / 60_000
    val sign = if (offsetMinutes >= 0) "+" else "-"
    val absMin = kotlin.math.abs(offsetMinutes)
    val hh = absMin / 60
    val mm = absMin % 60
    val offsetStr = String.format(Locale.US, "%s%02d:%02d", sign, hh, mm)
    exif.setAttribute("OffsetTime", offsetStr)
    exif.setAttribute("OffsetTimeOriginal", offsetStr)
    exif.setAttribute("OffsetTimeDigitized", offsetStr)

    val lat = (data["latitude"] as? Number)?.toDouble()
    val lng = (data["longitude"] as? Number)?.toDouble()
    if (lat != null && lng != null) {
      exif.setLatLong(lat, lng)
    }

    val altitude = (data["altitude"] as? Number)?.toDouble()
    if (altitude != null) {
      exif.setAltitude(altitude)
    }
  }

  private fun resolvePath(uriOrPath: String): String? {
    // Casos comuns: "file:///...", "content://...", "/storage/..."
    return try {
      when {
        uriOrPath.startsWith("file://") -> {
          val file = File(Uri.parse(uriOrPath).path ?: return null)
          if (file.exists()) file.absolutePath else null
        }
        uriOrPath.startsWith("content://") -> {
          val cr = appContext.reactContext?.contentResolver ?: return null
          val uri = Uri.parse(uriOrPath)
          // Copia o conteúdo para um arquivo temporário editável
          val tmp = File.createTempFile("ip-exif-", ".jpg", appContext.reactContext?.cacheDir)
          cr.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
          }
          // Nesse caso não conseguimos reescrever o conteúdo do asset original.
          // Retornamos o path do arquivo temporário (JS decide o que fazer).
          tmp.absolutePath
        }
        uriOrPath.startsWith("/") -> if (File(uriOrPath).exists()) uriOrPath else null
        else -> null
      }
    } catch (_: Throwable) {
      null
    }
  }

  private fun laplacianVariance(bitmap: android.graphics.Bitmap): Double {
    // Variância do Laplaciano em grayscale: score alto => mais nitidez.
    val w = bitmap.width
    val h = bitmap.height
    if (w < 3 || h < 3) return 0.0

    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    fun gray(px: Int): Int {
      val r = (px shr 16) and 0xFF
      val g = (px shr 8) and 0xFF
      val b = px and 0xFF
      return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    // 3x3 Laplacian kernel:
    //  0  1  0
    //  1 -4  1
    //  0  1  0
    var n = 0
    var sum = 0.0
    var sumSq = 0.0
    for (y in 1 until (h - 1)) {
      for (x in 1 until (w - 1)) {
        val c = gray(pixels[y * w + x])
        val up = gray(pixels[(y - 1) * w + x])
        val dn = gray(pixels[(y + 1) * w + x])
        val lf = gray(pixels[y * w + (x - 1)])
        val rt = gray(pixels[y * w + (x + 1)])
        val lap = (up + dn + lf + rt - 4 * c).toDouble()
        sum += lap
        sumSq += lap * lap
        n++
      }
    }
    if (n <= 1) return 0.0
    val mean = sum / n
    return (sumSq / n) - (mean * mean)
  }
}

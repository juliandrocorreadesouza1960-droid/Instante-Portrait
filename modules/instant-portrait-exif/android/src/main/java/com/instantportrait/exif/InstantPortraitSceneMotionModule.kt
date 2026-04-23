package com.instantportrait.exif

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class InstantPortraitSceneMotionModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("InstantPortraitSceneMotion")

    AsyncFunction("setSnapshotExposureNsAsync") { ns: Double ->
      val ctx =
        appContext.currentActivity?.applicationContext
          ?: appContext.reactContext?.applicationContext
          ?: throw IllegalStateException("no_context")
      val v = ns.toLong().coerceIn(100_000L, 100_000_000L)
      SceneMotionCameraView.writeSnapshotExposureNs(ctx, v)
      return@AsyncFunction mapOf("ok" to true, "exposureNs" to v)
    }

    AsyncFunction("setSnapshotIsoAsync") { iso: Double ->
      val ctx =
        appContext.currentActivity?.applicationContext
          ?: appContext.reactContext?.applicationContext
          ?: throw IllegalStateException("no_context")
      val v = iso.toInt().coerceIn(100, 6400)
      SceneMotionCameraView.writeSnapshotIso(ctx, v)
      return@AsyncFunction mapOf("ok" to true, "iso" to v)
    }

    AsyncFunction("getSnapshotExposureNsAsync") {
      val ctx =
        appContext.currentActivity?.applicationContext
          ?: appContext.reactContext?.applicationContext
          ?: throw IllegalStateException("no_context")
      val v = SceneMotionCameraView.readSnapshotExposureNs(ctx)
      return@AsyncFunction mapOf("ok" to true, "exposureNs" to v)
    }

    AsyncFunction("getSnapshotIsoAsync") {
      val ctx =
        appContext.currentActivity?.applicationContext
          ?: appContext.reactContext?.applicationContext
          ?: throw IllegalStateException("no_context")
      val v = SceneMotionCameraView.readSnapshotIso(ctx)
      return@AsyncFunction mapOf("ok" to true, "iso" to v)
    }

    View(SceneMotionCameraView::class) {
      Events("onScenePhoto", "onSceneReady", "onSceneError")

      Prop("analysisActive") { view: SceneMotionCameraView, active: Boolean? ->
        view.setAnalysisActive(active ?: false)
      }

      Prop("sensitivity") { view: SceneMotionCameraView, level: Int? ->
        view.setSensitivity(level ?: 2)
      }

      Prop("minIntervalMs") { view: SceneMotionCameraView, ms: Int? ->
        view.setMinCaptureIntervalMs(ms ?: 1000)
      }

      Prop("snapshotExposureNs") { view: SceneMotionCameraView, ns: Double? ->
        val v = (ns ?: 1_000_000_000.0 / 1000.0).toLong()
        view.setSnapshotExposureNs(v)
      }

      Prop("snapshotIso") { view: SceneMotionCameraView, iso: Double? ->
        val v = (iso ?: 1800.0).toInt()
        view.setSnapshotIso(v)
      }

      OnViewDestroys { view ->
        view.cleanupCamera()
      }
    }
  }
}

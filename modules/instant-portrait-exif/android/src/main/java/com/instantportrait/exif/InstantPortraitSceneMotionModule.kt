package com.instantportrait.exif

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class InstantPortraitSceneMotionModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("InstantPortraitSceneMotion")

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

      OnViewDestroys { view ->
        view.cleanupCamera()
      }
    }
  }
}

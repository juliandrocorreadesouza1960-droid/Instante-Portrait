import React from 'react';
import { requireNativeModule, requireNativeViewManager } from 'expo-modules-core';

const InstantPortraitExif = requireNativeModule('InstantPortraitExif');

const NativeSceneMotionView = requireNativeViewManager('InstantPortraitSceneMotion');

/**
 * Preview CameraX + detecção de movimento na cena (tripé).
 * Props: analysisActive, sensitivity, minIntervalMs, onScenePhoto, onSceneReady, onSceneError
 */
export function SceneMotionCameraView(props) {
  return React.createElement(NativeSceneMotionView, props);
}

export async function writeExifAsync(uri, data = {}) {
  return InstantPortraitExif.writeExifAsync(uri, data);
}

export async function saveJpegToGalleryAsync(uri, data = {}) {
  return InstantPortraitExif.saveJpegToGalleryAsync(uri, data);
}

export async function analyzeImageAsync(uri) {
  return InstantPortraitExif.analyzeImageAsync(uri);
}

export async function moveInGalleryAsync(uri, targetRelativePath) {
  return InstantPortraitExif.moveInGalleryAsync(uri, targetRelativePath);
}

export async function pingAsync() {
  return InstantPortraitExif.pingAsync();
}

export async function deleteFileAsync(uri) {
  return InstantPortraitExif.deleteFileAsync(uri);
}

/** Android: abre a pasta do MediaStore. which: 'kept' | 'rejected' */
export async function openInstantPortraitFolderAsync(which) {
  return InstantPortraitExif.openInstantPortraitFolderAsync(which);
}

export default {
  writeExifAsync,
  saveJpegToGalleryAsync,
  analyzeImageAsync,
  moveInGalleryAsync,
  pingAsync,
  deleteFileAsync,
  openInstantPortraitFolderAsync,
  SceneMotionCameraView,
};

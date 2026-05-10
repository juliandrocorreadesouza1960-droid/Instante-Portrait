'use strict';

/**
 * Gera APK release + AAB (Play) numa só invocação do Gradle.
 *
 * Uso:
 *   npm run android:release:prod
 *   set ANDROID_FLAVOR=enhance && npm run android:release:prod   (Windows CMD)
 *   ANDROID_FLAVOR=enhance npm run android:release:prod         (Unix)
 */

const { spawnSync } = require('child_process');
const path = require('path');

const root = path.join(__dirname, '..');
const androidDir = path.join(root, 'android');
const rawFlavor = (process.env.ANDROID_FLAVOR || 'prod').trim().toLowerCase();
const flavorGradle = rawFlavor.charAt(0).toUpperCase() + rawFlavor.slice(1);
const assembleTask = `assemble${flavorGradle}Release`;
const bundleTask = `bundle${flavorGradle}Release`;

const isWin = process.platform === 'win32';
const gradlew = isWin ? 'gradlew.bat' : './gradlew';

const result = spawnSync(gradlew, [assembleTask, bundleTask], {
  cwd: androidDir,
  stdio: 'inherit',
  shell: isWin,
  env: process.env,
});

const code = result.status;
if (code !== 0) {
  process.exit(code == null ? 1 : code);
}

const apkName = rawFlavor === 'enhance' ? 'AutoFrame-Enhance.apk' : 'AutoFrame.apk';
console.log('\n--- Artefactos (release) ---');
console.log(
  `APK: ${path.join('android', 'app', 'build', 'outputs', 'apk', rawFlavor, 'release', apkName)}`
);
console.log(
  `AAB: android/app/build/outputs/bundle/${rawFlavor}Release/app-${rawFlavor}-release.aab`
);
console.log('');

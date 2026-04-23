import { StatusBar } from 'expo-status-bar';
import * as Camera from 'expo-camera';
import * as Location from 'expo-location';
import { useKeepAwake } from 'expo-keep-awake';
import {
  analyzeImageAsync,
  deleteFileAsync,
  moveInGalleryAsync,
  openInstantPortraitFolderAsync,
  pingAsync,
  saveJpegToGalleryAsync,
  SceneMotionCameraView,
} from 'instant-portrait-exif';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import {
  ActivityIndicator,
  Alert,
  BackHandler,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

const CAPTURE_MODES = {
  TIME: 'time',
  MOTION: 'motion',
};

/** Intervalo de cadência (modo Tempo) e mínimo entre disparos (modo Movimento). */
const INTERVAL_CHOICES_MS = [500, 1000, 1500, 2000];

function formatIntervalLabel(ms) {
  if (ms === 500) return '0,5s';
  return `${ms / 1000}s`;
}

/** Texto curto do cabeçalho: ritmo (modo tempo) ou intervalo mínimo (cena). */
function headerCadenceLine(mode, intervalMs, motionMinIntervalMs) {
  if (mode === CAPTURE_MODES.MOTION) {
    return `Mín. ${formatIntervalLabel(motionMinIntervalMs)} entre fotos na cena`;
  }
  if (intervalMs === 1000) return '1 foto por segundo';
  if (intervalMs === 500) return '1 foto a cada 0,5 s';
  if (intervalMs === 1500) return '1 foto a cada 1,5 s';
  if (intervalMs === 2000) return '1 foto a cada 2 s';
  return `1 foto a cada ${formatIntervalLabel(intervalMs)}`;
}

/** Card com filete de destaque no topo (estilo apps esportivos / PULSO). */
function SectionCard({ title, children, s }) {
  return (
    <View style={s.card}>
      <View style={s.cardTopAccent} />
      <View style={s.cardBody}>
        {title ? <Text style={s.cardTitle}>{title}</Text> : null}
        {children}
      </View>
    </View>
  );
}

function StatRowCard({ children, s }) {
  return (
    <View style={s.cardStat}>
      <View style={s.cardStatAccent} />
      <View style={s.cardStatInner}>{children}</View>
    </View>
  );
}

export default function App() {
  useKeepAwake();

  const cameraRef = useRef(null);
  const timerRef = useRef(null);
  const captureInFlightRef = useRef(false);
  const isRunningRef = useRef(false); // fonte da verdade "captura ativa?" (síncrono, à prova de race com Parar)
  const heartbeatRef = useRef(null);
  const cameraIsReadyRef = useRef(false);
  /** Incrementado em timeout de captura para ignorar resultado de `takePictureAsync` “fantasma”. */
  const captureGenRef = useRef(0);
  const locationSubRef = useRef(null);
  const reviewQueueRef = useRef([]);
  const reviewInFlightRef = useRef(false);
  const saveQueueRef = useRef([]);
  const saveInFlightRef = useRef(false);
  const lastCoordsRef = useRef(null); // snapshot síncrono das coords pra usar no save em background
  const autoCullEnabledRef = useRef(false);
  const cullNoPeopleRef = useRef(true);
  const cullBlurRef = useRef(false);
  const blurStrictnessRef = useRef(2);
  const intervalMsRef = useRef(1000);

  const [cameraPermission, requestCameraPermission] = Camera.useCameraPermissions();
  const [locationPermission, requestLocationPermission] = Location.useForegroundPermissions();

  const [ready, setReady] = useState(false);
  const [isCapturing, setIsCapturing] = useState(false);
  const [lastError, setLastError] = useState(null);

  const [shotCount, setShotCount] = useState(0);
  const [intervalMs, setIntervalMs] = useState(1000);
  const [lastCoords, setLastCoords] = useState(null);
  const [mode, setMode] = useState(CAPTURE_MODES.TIME);
  const [motionSensitivity, setMotionSensitivity] = useState(2); // 1=baixa, 2=normal, 3=alta
  /** No modo cena: tempo mínimo entre dois disparos (nativo; limita rajadas). */
  const [motionMinIntervalMs, setMotionMinIntervalMs] = useState(1000);

  // Filtragem automática (opcional)
  const [autoCullEnabled, setAutoCullEnabled] = useState(false);
  const [cullNoPeople, setCullNoPeople] = useState(true);
  const [cullBlur, setCullBlur] = useState(false);
  const [blurStrictness, setBlurStrictness] = useState(2); // 1=leniente, 2=normal, 3=rigoroso
  const [keptCount, setKeptCount] = useState(0);
  const [rejectedCount, setRejectedCount] = useState(0);
  const [screen, setScreen] = useState('config'); // 'config' | 'capture'

  const [pictureSizes, setPictureSizes] = useState([]);
  const [selectedPictureSize, setSelectedPictureSize] = useState(null);
  const [selectedRatio, setSelectedRatio] = useState(null);
  const [cameraIsReady, setCameraIsReady] = useState(false);
  const [cameraKey, setCameraKey] = useState(0); // remount manual ao entrar na tela de captura

  // Diagnóstico visível na tela de captura (ajuda a achar gargalos no aparelho real)
  const [saveQueueSize, setSaveQueueSize] = useState(0);
  const [reviewQueueSize, setReviewQueueSize] = useState(0);
  const [lastShotMs, setLastShotMs] = useState(null); // tempo gasto no último disparo (ms)
  const [lastSaveMs, setLastSaveMs] = useState(null); // tempo gasto no último save (ms)

  /**
   * Limite de lado longo ao gravar no MediaStore. `0` = não reduz o JPEG
   * (o valor 3000 antigo forçava ~2250×3000 e falhava requisitos tipo 3450×2300).
   * `minOutputWidthPx` / `minOutputHeightPx` reescalam par acima, se a captura for menor.
   */
  const maxSidePx = 0;
  const uploadMinWidthPx = 3450;
  const uploadMinHeightPx = 2300;

  const hasAllPermissions = useMemo(() => {
    return Boolean(
      cameraPermission?.granted &&
        // localização é “best effort” pro MVP (EXIF de data/hora é obrigatório)
        (locationPermission?.granted ?? true)
    );
  }, [cameraPermission?.granted, locationPermission?.granted]);

  useEffect(() => {
    let cancelled = false;

    async function bootstrap() {
      setLastError(null);
      setCameraIsReady(false);

      const cam = await requestCameraPermission();
      // não bloqueia se o usuário negar; a gente salva sem GPS
      await requestLocationPermission().catch(() => null);

      if (cancelled) return;

      if (!cam?.granted) {
        setReady(true);
        return;
      }

      setReady(true);
    }

    bootstrap().catch((e) => setLastError(String(e?.message ?? e)));

    return () => {
      cancelled = true;
    };
  }, [requestCameraPermission, requestLocationPermission]);

  useEffect(() => {
    // Sempre que remonta a câmera, volta para "não pronta"
    setCameraIsReady(false);
    cameraIsReadyRef.current = false;
  }, [cameraKey]);

  // Espelha estados em refs síncronos para uso dentro de workers/timers sem stale closure.
  useEffect(() => {
    lastCoordsRef.current = lastCoords;
  }, [lastCoords]);
  useEffect(() => {
    autoCullEnabledRef.current = autoCullEnabled;
  }, [autoCullEnabled]);
  useEffect(() => {
    cullNoPeopleRef.current = cullNoPeople;
  }, [cullNoPeople]);
  useEffect(() => {
    cullBlurRef.current = cullBlur;
  }, [cullBlur]);
  useEffect(() => {
    blurStrictnessRef.current = blurStrictness;
  }, [blurStrictness]);
  useEffect(() => {
    intervalMsRef.current = intervalMs;
  }, [intervalMs]);
  const motionSensitivityRef = useRef(2);
  useEffect(() => {
    motionSensitivityRef.current = motionSensitivity;
  }, [motionSensitivity]);

  useEffect(() => {
    cameraIsReadyRef.current = cameraIsReady;
  }, [cameraIsReady]);

  useEffect(() => {
    // Ao entrar em "Capturar", remonta o preview para pegar surface fresco (Samsung/CameraX).
    if (screen === 'capture') {
      setCameraKey((k) => k + 1);
    } else {
      // Em "Configurar" a câmera fica desmontada para não brigar com o SurfaceView.
      setCameraIsReady(false);
      cameraIsReadyRef.current = false;
    }
  }, [screen]);

  function stopCapture() {
    // 1) marca a sessão como encerrada ANTES de tudo — o loop verifica isso
    //    no finally do tick e para de reenfileirar setTimeout.
    isRunningRef.current = false;

    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    if (heartbeatRef.current) {
      clearInterval(heartbeatRef.current);
      heartbeatRef.current = null;
    }
    captureInFlightRef.current = false;
    // Invalida capturas em andamento (timeout/recovery) para não “vazar” foto após Parar.
    captureGenRef.current += 1;
    if (locationSubRef.current) {
      try {
        locationSubRef.current.remove();
      } catch (_) {}
      locationSubRef.current = null;
    }
    setIsCapturing(false);
  }

  async function takePictureWithRetry(cam) {
    // skipProcessing=true faz a câmera devolver o JPEG direto, sem rotacionar o buffer no JS
    // (a orientação vai via EXIF, preservada pelo módulo nativo ao salvar). Isso reduz o
    // tempo de captura de 2-3s para ~300-700ms e elimina a maioria dos ERR_IMAGE_CAPTURE_FAILED.
    const opts = { quality: 1, exif: true, skipProcessing: true };
    try {
      return await cam.takePictureAsync(opts);
    } catch (e1) {
      // CameraX às vezes engasga em 1 disparo isolado; 1 retry curto resolve.
      await new Promise((r) => setTimeout(r, 250));
      try {
        return await cam.takePictureAsync(opts);
      } catch (e2) {
        const err = new Error(String(e2?.message ?? e2));
        err.code = e2?.code ?? e1?.code ?? 'ERR_CAPTURE';
        throw err;
      }
    }
  }

  function waitForCameraReady(maxMs) {
    return new Promise((resolve) => {
      const t0 = Date.now();
      const id = setInterval(() => {
        if (!isRunningRef.current) {
          clearInterval(id);
          resolve(false);
        } else if (cameraIsReadyRef.current) {
          clearInterval(id);
          resolve(true);
        } else if (Date.now() - t0 >= maxMs) {
          clearInterval(id);
          resolve(false);
        }
      }, 40);
    });
  }

  /**
   * Samsung A55 + expo-camera: o 2º `takePictureAsync` pode ficar 10–30s pendurado se o
   * CameraX entra em estado ruim. Timeout + remount da surface costuma destravar.
   */
  async function takePictureWithTimeoutRecovery(cam) {
    const genAtStart = captureGenRef.current;
    const CAP_MS = 4500;
    const p = takePictureWithRetry(cam);
    let timer = null;
    const timeoutP = new Promise((_, rej) => {
      timer = setTimeout(() => rej(Object.assign(new Error('CAPTURE_TIMEOUT'), { code: 'TIMEOUT' })), CAP_MS);
    });
    try {
      const photo = await Promise.race([p, timeoutP]);
      if (timer) clearTimeout(timer);
      if (genAtStart !== captureGenRef.current) return null;
      return photo;
    } catch (e) {
      if (timer) clearTimeout(timer);
      if (e?.code !== 'TIMEOUT') throw e;

      // Invalida qualquer conclusão tardia do takePicture preso.
      captureGenRef.current += 1;
      p.catch(() => null);

      setLastError('Câmera engasgou — reciclando preview…');
      setCameraIsReady(false);
      cameraIsReadyRef.current = false;
      setCameraKey((k) => k + 1);

      const ok = await waitForCameraReady(12000);
      const cam2 = cameraRef.current;
      if (!ok || !cam2 || !cameraIsReadyRef.current) {
        setLastError('Câmera não voltou a tempo após reciclar. Tente Config → Captura.');
        return null;
      }
      if (!isRunningRef.current) return null;

      const genRetry = captureGenRef.current;
      await new Promise((r) => setTimeout(r, 200));
      const photo2 = await takePictureWithRetry(cam2);
      if (genRetry !== captureGenRef.current) return null;
      return photo2;
    }
  }

  async function captureOnce() {
    const cam = cameraRef.current;
    if (!cam) return;
    if (!cameraIsReadyRef.current) {
      // não polui a UI com erro: só pula o tick e espera a câmera ficar pronta.
      return;
    }

    let photo = null;
    const t0 = Date.now();
    try {
      photo = await takePictureWithTimeoutRecovery(cam);
    } catch (e) {
      const msg = String(e?.message ?? e);
      const code = e?.code ? ` (${String(e.code)})` : '';
      // Falha de 1 disparo NÃO derruba a sessão. O loop segue.
      setLastError(`Foto falhou: ${msg}${code}`);
      return;
    }

    if (!photo || !photo.uri) {
      if (isRunningRef.current) {
        setLastError('A câmera não devolveu imagem neste disparo. Seguindo…');
      }
      return;
    }

    // Se o usuário parou durante um disparo longo, não conta nem salva a foto “fantasma”.
    if (!isRunningRef.current) {
      return;
    }

    enqueueCaptureFromFileUri(String(photo.uri), Date.now() - t0);
  }

  /** Modo tempo (expo-camera) ou modo movimento na cena (CameraX nativo): fila save + EXIF. */
  function enqueueCaptureFromFileUri(srcUri, dispMs) {
    if (!isRunningRef.current) return;
    setLastError(null);
    setShotCount((c) => c + 1);
    if (typeof dispMs === 'number' && !Number.isNaN(dispMs)) {
      setLastShotMs(dispMs);
    }
    const coords = lastCoordsRef.current;
    saveQueueRef.current.push({
      srcUri: String(srcUri),
      meta: {
        timestampMs: Date.now(),
        latitude: coords?.latitude ?? null,
        longitude: coords?.longitude ?? null,
        altitude: coords?.altitude ?? null,
        maxSidePx,
        minOutputWidthPx: uploadMinWidthPx,
        minOutputHeightPx: uploadMinHeightPx,
      },
    });
    setSaveQueueSize(saveQueueRef.current.length);
    kickSaveWorker();
  }

  function onSceneMotionPhoto(e) {
    const uri = e?.nativeEvent?.uri;
    if (!uri || typeof uri !== 'string') return;
    if (!isRunningRef.current) return;
    enqueueCaptureFromFileUri(uri, 0);
  }

  function onSceneMotionReady() {
    setTimeout(() => {
      cameraIsReadyRef.current = true;
      setCameraIsReady(true);
    }, 150);
  }

  function onSceneMotionError(e) {
    const msg = e?.nativeEvent?.message ?? 'Falha na câmera (movimento na cena)';
    setCameraIsReady(false);
    cameraIsReadyRef.current = false;
    setLastError(String(msg));
  }

  function kickSaveWorker() {
    if (saveInFlightRef.current) return;
    saveInFlightRef.current = true;

    const work = async () => {
      while (saveQueueRef.current.length > 0) {
        const item = saveQueueRef.current.shift();
        setSaveQueueSize(saveQueueRef.current.length);
        if (!item) continue;
        const ts0 = Date.now();
        try {
          const saved = await saveJpegToGalleryAsync(item.srcUri, item.meta);
          setLastSaveMs(Date.now() - ts0);
          // Limpa cache do expo-camera com atraso (apagar cedo demais pode brigar com o próximo disparo).
          setTimeout(() => deleteFileAsync(item.srcUri).catch(() => null), 8000);
          if (autoCullEnabledRef.current && saved?.uri) {
            reviewQueueRef.current.push(String(saved.uri));
            setReviewQueueSize(reviewQueueRef.current.length);
            kickReviewWorker();
          } else {
            setKeptCount((c) => c + 1);
          }
        } catch (e) {
          setLastError(
            `Não consegui salvar a foto no DCIM. ${String(e?.message ?? e)}`
          );
        }
      }
    };

    work()
      .catch(() => null)
      .finally(() => {
        saveInFlightRef.current = false;
        if (saveQueueRef.current.length > 0) kickSaveWorker();
      });
  }

  function kickReviewWorker() {
    if (reviewInFlightRef.current) return;
    reviewInFlightRef.current = true;

    const work = async () => {
      while (reviewQueueRef.current.length > 0) {
        const uri = reviewQueueRef.current.shift();
        setReviewQueueSize(reviewQueueRef.current.length);
        if (!uri) continue;

        try {
          const res = await analyzeImageAsync(uri);
          const faces = Number(res?.faces ?? 0);
          const hasPerson = Boolean(res?.hasPerson ?? (faces > 0));
          const blurScore = Number(res?.blurScore ?? 0);

          let reject = false;
          if (cullNoPeopleRef.current && !hasPerson) reject = true;

          if (cullBlurRef.current) {
            // thresholds empiricamente seguros para imagens 1440x1920/3MP;
            // leniente aceita mais (threshold menor), rigoroso rejeita mais (threshold maior).
            const bs = blurStrictnessRef.current;
            const thr = bs === 1 ? 45 : bs === 3 ? 110 : 75;
            if (blurScore < thr) reject = true;
          }

          if (reject) {
            await moveInGalleryAsync(uri, 'DCIM/Instant Portrait/Rejected');
            setRejectedCount((c) => c + 1);
          } else {
            setKeptCount((c) => c + 1);
          }
        } catch (_) {
          // Se falhar a filtragem, mantém a foto (fail-open).
          setKeptCount((c) => c + 1);
        }
      }
    };

    work()
      .catch(() => null)
      .finally(() => {
        reviewInFlightRef.current = false;
        // se entrou algo enquanto processava
        if (reviewQueueRef.current.length > 0) kickReviewWorker();
      });
  }

  async function startIntervalCapture() {
    if (isCapturing || isRunningRef.current) return;
    isRunningRef.current = true;
    setShotCount(0);
    setKeptCount(0);
    setRejectedCount(0);
    setIsCapturing(true);
    setLastError(null);

    // GPS em background: evita travar a captura esperando fix de localização.
    if (locationPermission?.granted && !locationSubRef.current) {
      try {
        locationSubRef.current = await Location.watchPositionAsync(
          {
            accuracy: Location.Accuracy.Balanced,
            timeInterval: 5000,
            distanceInterval: 1,
          },
          (pos) => {
            const c = pos?.coords;
            if (!c) return;
            setLastCoords({
              latitude: c.latitude,
              longitude: c.longitude,
              altitude: c.altitude ?? null,
            });
          }
        );
      } catch (_) {
        // best effort
      }
    }

    // Heartbeat: mantém o bridge/JVM ativos enquanto capturamos. Sem isso, o
    // takePictureAsync do expo-camera entra em idle e leva 5-25s para devolver
    // a foto quando não há outra atividade (como a filtragem ML Kit) ocupando o processo.
    if (heartbeatRef.current) clearInterval(heartbeatRef.current);
    heartbeatRef.current = setInterval(() => {
      if (!isRunningRef.current) return;
      pingAsync().catch(() => null);
    }, 200);

    // Cadência: pausa FIXA após cada disparo (usa intervalMsRef para refletir mudanças no Config).
    // Não usamos (intervalMs - tempoDaCaptura): quando a captura ou o nativo atrasa, isso zera
    // o sleep e dispara 2 fotos seguidas (“recuperando tempo”), que é exatamente o que você viu.
    const runLoop = async () => {
      while (isRunningRef.current) {
        if (captureInFlightRef.current) {
          await new Promise((r) => setTimeout(r, 50));
          continue;
        }
        captureInFlightRef.current = true;
        try {
          await captureOnce();
        } catch (_) {
          // erros isolados não devem derrubar a sessão
        } finally {
          captureInFlightRef.current = false;
        }
        if (!isRunningRef.current) break;
        const pause = Math.max(0, intervalMsRef.current);
        await new Promise((r) => {
          timerRef.current = setTimeout(r, pause);
        });
      }
    };

    runLoop().catch(() => null);
  }

  async function startMotionCapture() {
    if (isCapturing || isRunningRef.current) return;
    isRunningRef.current = true;
    setShotCount(0);
    setKeptCount(0);
    setRejectedCount(0);
    setIsCapturing(true);
    setLastError(null);

    // GPS em background (mesma lógica do modo tempo). O disparo vem da vista nativa
    // SceneMotion (CameraX ImageAnalysis) com o celular parado no tripé.
    if (locationPermission?.granted && !locationSubRef.current) {
      try {
        locationSubRef.current = await Location.watchPositionAsync(
          {
            accuracy: Location.Accuracy.Balanced,
            timeInterval: 5000,
            distanceInterval: 1,
          },
          (pos) => {
            const c = pos?.coords;
            if (!c) return;
            setLastCoords({
              latitude: c.latitude,
              longitude: c.longitude,
              altitude: c.altitude ?? null,
            });
          }
        );
      } catch (_) {}
    }

    if (heartbeatRef.current) clearInterval(heartbeatRef.current);
    heartbeatRef.current = setInterval(() => {
      if (!isRunningRef.current) return;
      pingAsync().catch(() => null);
    }, 200);
  }

  async function startCapture() {
    if (mode === CAPTURE_MODES.MOTION) return startMotionCapture();
    return startIntervalCapture();
  }

  async function onCameraReady() {
    const cam = cameraRef.current;
    if (!cam) return;

    try {
      // Pequeno delay evita capture/preview race em alguns Samsung
      setTimeout(() => {
        cameraIsReadyRef.current = true;
        setCameraIsReady(true);
      }, 250);
      // MODO COMPATIBILIDADE:
      // Não forçamos ratio/pictureSize no CameraView (isso pode causar preview preto e capture fail).
      // Aqui a gente só tenta listar sizes para exibir na UI (best effort).
      const sizes = await cam.getAvailablePictureSizesAsync('4:3').catch(() => []);
      setPictureSizes(sizes);

      const parsed = sizes
        .map((s) => {
          const [wStr, hStr] = String(s).split('x');
          const w = Number(wStr);
          const h = Number(hStr);
          const maxSide = Math.max(w, h);
          return { raw: s, w, h, maxSide };
        })
        .filter((x) => Number.isFinite(x.w) && Number.isFinite(x.h))
        .filter((x) => (maxSidePx > 0 ? x.maxSide <= maxSidePx : true))
        .sort((a, b) => b.maxSide - a.maxSide);

      setSelectedPictureSize(parsed[0]?.raw ?? sizes?.[sizes.length - 1] ?? null);
      setSelectedRatio(null);
    } catch (e) {
      setLastError(String(e?.message ?? e));
    }
  }

  const canStart = ready && hasAllPermissions && cameraIsReady && !isCapturing;
  const canStop = isCapturing;

  const showGalleryFolders = useCallback(() => {
    if (Platform.OS !== 'android') {
      Alert.alert(
        'Galeria',
        'Neste dispositivo, abra a app de fotos e procure o álbum ou pasta do Instant Portrait.'
      );
      return;
    }
    Alert.alert('Galeria', 'Abrir qual pasta no armazenamento?', [
      {
        text: 'Fotos boas',
        onPress: () => {
          openInstantPortraitFolderAsync('kept')
            .then((r) => {
              if (r && r.ok === false) {
                Alert.alert(
                  'Galeria',
                  'Não foi possível abrir a pasta automaticamente. No telefone, abra a galeria e localize DCIM/Instant Portrait.'
                );
              }
            })
            .catch(() => {
              Alert.alert(
                'Galeria',
                'Não foi possível abrir a pasta. Procure a pasta DCIM/Instant Portrait na galeria de fotos.'
              );
            });
        },
      },
      {
        text: 'Descartar',
        onPress: () => {
          openInstantPortraitFolderAsync('rejected')
            .then((r) => {
              if (r && r.ok === false) {
                Alert.alert(
                  'Galeria',
                  'Não foi possível abrir a pasta. Procure DCIM/Instant Portrait/Rejected na galeria de fotos.'
                );
              }
            })
            .catch(() => {
              Alert.alert(
                'Galeria',
                'Não foi possível abrir a pasta. Procure a pasta de descartes na galeria.'
              );
            });
        },
      },
      { text: 'Cancelar', style: 'cancel' },
    ]);
  }, []);

  useEffect(() => {
    if (Platform.OS !== 'android') return;
    if (screen !== 'capture') return;
    const onBack = () => {
      if (isCapturing) {
        stopCapture();
      }
      setScreen('config');
      return true;
    };
    const sub = BackHandler.addEventListener('hardwareBackPress', onBack);
    return () => sub.remove();
  }, [screen, isCapturing, stopCapture]);

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.safe} edges={['top', 'bottom']}>
        <View style={styles.header}>
          <View style={styles.headerAccent} />
          <View style={styles.headerRow}>
            <View style={styles.headerTextCol}>
              <Text style={styles.eyebrow}>Instant Portrait</Text>
              <Text style={styles.title}>
                {mode === CAPTURE_MODES.MOTION ? 'Cena' : 'Intervalo'}
              </Text>
              <Text style={styles.subtitle} numberOfLines={2}>
                {headerCadenceLine(mode, intervalMs, motionMinIntervalMs)}
              </Text>
            </View>

            <Pressable
              onPress={showGalleryFolders}
              style={({ pressed }) => [styles.badge, pressed && styles.badgePressed]}
            >
              <Text style={styles.badgeLabel}>Fotos</Text>
              <Text style={styles.badgeValue}>{shotCount}</Text>
              <Text style={styles.badgeHint}>tocar p/ pastas</Text>
            </Pressable>
          </View>
        </View>

        {screen === 'capture' ? (
          <View style={styles.previewWrap}>
            {mode === CAPTURE_MODES.MOTION ? (
              <SceneMotionCameraView
                key={cameraKey}
                style={styles.preview}
                analysisActive={isCapturing}
                sensitivity={motionSensitivity}
                minIntervalMs={motionMinIntervalMs}
                onScenePhoto={onSceneMotionPhoto}
                onSceneReady={onSceneMotionReady}
                onSceneError={onSceneMotionError}
              />
            ) : (
              <Camera.CameraView
                key={cameraKey}
                ref={cameraRef}
                style={styles.preview}
                facing="back"
                onCameraReady={onCameraReady}
                onMountError={(e) => {
                  setCameraIsReady(false);
                  cameraIsReadyRef.current = false;
                  setLastError(`Falha ao iniciar a câmera: ${String(e?.message ?? e)}`);
                }}
              />
            )}
          </View>
        ) : !ready ? (
          <View style={styles.previewStub}>
            <ActivityIndicator color="#FF6B2C" />
            <Text style={styles.muted}>A preparar permissões…</Text>
          </View>
        ) : !hasAllPermissions ? (
          <View style={styles.previewStub}>
            <Text style={styles.errorTitle}>Permissões necessárias</Text>
            <Text style={styles.muted}>
              Conceda acesso à câmara para poder usar a captura.
            </Text>
            <Pressable
              style={({ pressed }) => [styles.primaryBtn, styles.primaryBtnWide, pressed && styles.btnPressed]}
              onPress={async () => {
                const cam = await requestCameraPermission();
                await requestLocationPermission().catch(() => null);
                if (!cam?.granted) {
                  setLastError('Câmara em falta. Conceda a permissão nas definições do telemóvel, se for preciso.');
                } else {
                  setLastError(null);
                }
              }}
            >
              <Text style={styles.primaryBtnText}>Conceder permissões</Text>
            </Pressable>
          </View>
        ) : null}

        {screen === 'config' ? (
          <ScrollView
            style={styles.controls}
            contentContainerStyle={styles.controlsContent}
            showsVerticalScrollIndicator={false}
          >
            <SectionCard title="Captura" s={styles}>
              <View style={styles.row}>
                <Text style={styles.label}>Modo</Text>
                <View style={styles.pills}>
                  {[
                    { id: CAPTURE_MODES.TIME, label: 'Por tempo' },
                    { id: CAPTURE_MODES.MOTION, label: 'Cena' },
                  ].map((opt) => {
                    const active = mode === opt.id;
                    return (
                      <Pressable
                        key={opt.id}
                        style={({ pressed }) => [
                          styles.pill,
                          active && styles.pillActive,
                          pressed && styles.btnPressed,
                        ]}
                        onPress={() => setMode(opt.id)}
                        disabled={isCapturing}
                      >
                        <Text style={[styles.pillText, active && styles.pillTextActive]}>
                          {opt.label}
                        </Text>
                      </Pressable>
                    );
                  })}
                </View>
              </View>

              {mode === CAPTURE_MODES.TIME ? (
                <View style={styles.row}>
                  <Text style={styles.label}>Intervalo</Text>
                  <View style={styles.pills}>
                    {INTERVAL_CHOICES_MS.map((ms) => {
                      const active = intervalMs === ms;
                      return (
                        <Pressable
                          key={ms}
                          style={({ pressed }) => [
                            styles.pill,
                            active && styles.pillActive,
                            pressed && styles.btnPressed,
                          ]}
                          onPress={() => setIntervalMs(ms)}
                          disabled={isCapturing}
                        >
                          <Text style={[styles.pillText, active && styles.pillTextActive]}>
                            {formatIntervalLabel(ms)}
                          </Text>
                        </Pressable>
                      );
                    })}
                  </View>
                </View>
              ) : (
                <>
                  <View style={styles.row}>
                    <Text style={styles.label}>Mín. entre disparos</Text>
                    <View style={styles.pills}>
                      {INTERVAL_CHOICES_MS.map((ms) => {
                        const active = motionMinIntervalMs === ms;
                        return (
                          <Pressable
                            key={ms}
                            style={({ pressed }) => [
                              styles.pill,
                              active && styles.pillActive,
                              pressed && styles.btnPressed,
                            ]}
                            onPress={() => setMotionMinIntervalMs(ms)}
                            disabled={isCapturing}
                          >
                            <Text style={[styles.pillText, active && styles.pillTextActive]}>
                              {formatIntervalLabel(ms)}
                            </Text>
                          </Pressable>
                        );
                      })}
                    </View>
                  </View>
                  <View style={styles.row}>
                    <Text style={styles.label}>Sensibilidade</Text>
                    <View style={styles.pills}>
                      {[
                        { id: 1, label: 'Baixa' },
                        { id: 2, label: 'Normal' },
                        { id: 3, label: 'Alta' },
                      ].map((s) => {
                        const active = motionSensitivity === s.id;
                        return (
                          <Pressable
                            key={s.id}
                            style={({ pressed }) => [
                              styles.pill,
                              active && styles.pillActive,
                              pressed && styles.btnPressed,
                            ]}
                            onPress={() => setMotionSensitivity(s.id)}
                            disabled={isCapturing}
                          >
                            <Text style={[styles.pillText, active && styles.pillTextActive]}>
                              {s.label}
                            </Text>
                          </Pressable>
                        );
                      })}
                    </View>
                  </View>
                </>
              )}
            </SectionCard>

            <SectionCard title="Filtragem (opcional)" s={styles}>
              <View style={styles.row}>
              <Text style={styles.labelInline}>Automática</Text>
              <View style={styles.pills}>
                {[
                  { id: 'off', label: 'Desligada' },
                  { id: 'on', label: 'Ligada' },
                ].map((opt) => {
                  const active = autoCullEnabled ? opt.id === 'on' : opt.id === 'off';
                  return (
                    <Pressable
                      key={opt.id}
                      style={({ pressed }) => [
                        styles.pill,
                        active && styles.pillActive,
                        pressed && styles.btnPressed,
                      ]}
                      onPress={() => setAutoCullEnabled(opt.id === 'on')}
                      disabled={isCapturing}
                    >
                      <Text style={[styles.pillText, active && styles.pillTextActive]}>
                        {opt.label}
                      </Text>
                    </Pressable>
                  );
                })}
                </View>
              </View>

              {autoCullEnabled ? (
                <View style={[styles.pills, { marginTop: 8 }]}>
                  {[
                    { id: 'people', label: 'Sem pessoas', value: cullNoPeople, setter: setCullNoPeople },
                    { id: 'blur', label: 'Embaçada', value: cullBlur, setter: setCullBlur },
                  ].map((t) => {
                    const active = Boolean(t.value);
                    return (
                      <Pressable
                        key={t.id}
                        style={({ pressed }) => [
                          styles.pill,
                          active && styles.pillActive,
                          pressed && styles.btnPressed,
                        ]}
                        onPress={() => t.setter(!t.value)}
                        disabled={isCapturing}
                      >
                        <Text style={[styles.pillText, active && styles.pillTextActive]}>
                          {t.label}
                        </Text>
                      </Pressable>
                    );
                  })}
                </View>
              ) : null}

              {autoCullEnabled && cullBlur ? (
                <View style={[styles.pills, { marginTop: 8 }]}>
                  {[
                    { id: 1, label: 'Blur: Leniente' },
                    { id: 2, label: 'Blur: Normal' },
                    { id: 3, label: 'Blur: Rigoroso' },
                  ].map((s) => {
                    const active = blurStrictness === s.id;
                    return (
                      <Pressable
                        key={s.id}
                        style={({ pressed }) => [
                          styles.pill,
                          active && styles.pillActive,
                          pressed && styles.btnPressed,
                        ]}
                        onPress={() => setBlurStrictness(s.id)}
                        disabled={isCapturing}
                      >
                        <Text style={[styles.pillText, active && styles.pillTextActive]}>
                          {s.label}
                        </Text>
                      </Pressable>
                    );
                  })}
                </View>
              ) : null}
            </SectionCard>

            <StatRowCard s={styles}>
              <Text style={styles.mutedSmall}>
                Última sessão: Fotos boas <Text style={styles.subtitleStrong}>{keptCount}</Text> •
                Descartar <Text style={styles.subtitleStrong}>{rejectedCount}</Text>
              </Text>
            </StatRowCard>

            {lastError ? (
              <View style={styles.errorBox}>
                <Text style={styles.errorTitle}>Erro</Text>
                <Text style={styles.errorText}>{lastError}</Text>
              </View>
            ) : null}

            <View style={styles.actionsSingle}>
              <Pressable
                style={({ pressed }) => [
                  styles.primaryBtn,
                  styles.primaryBtnWide,
                  !ready && styles.btnDisabled,
                  pressed && styles.btnPressed,
                ]}
                onPress={() => setScreen('capture')}
                disabled={!ready}
              >
                <Text style={styles.primaryBtnText}>Abrir visor</Text>
              </Pressable>
            </View>
          </ScrollView>
        ) : (
          <View style={styles.captureBar}>
            <View style={styles.captureMeta}>
              <View style={styles.captureMetaAccent} />
              <View style={styles.captureMetaInner}>
                <Text style={styles.mutedSmall}>
                  Fotos boas: <Text style={styles.subtitleStrong}>{keptCount}</Text>
                  {'  ·  '}
                  Descartar: <Text style={styles.subtitleStrong}>{rejectedCount}</Text>
                </Text>
                {lastError ? (
                  <Text style={[styles.mutedSmall, { color: '#FFB4B4' }]} numberOfLines={2}>
                    {lastError}
                  </Text>
                ) : null}
              </View>
            </View>

            <View style={styles.actions}>
              <Pressable
                style={({ pressed }) => [
                  styles.primaryBtn,
                  !canStart && styles.btnDisabled,
                  pressed && styles.btnPressed,
                ]}
                onPress={startCapture}
                disabled={!canStart}
              >
                <Text style={styles.primaryBtnText}>Iniciar</Text>
              </Pressable>

              <Pressable
                style={({ pressed }) => [
                  styles.secondaryBtn,
                  !canStop && styles.btnDisabled,
                  pressed && styles.btnPressed,
                ]}
                onPress={stopCapture}
                disabled={!canStop}
              >
                <Text style={styles.secondaryBtnText}>Parar</Text>
              </Pressable>

              <Pressable
                style={({ pressed }) => [
                  styles.ghostBtn,
                  pressed && styles.btnPressed,
                ]}
                onPress={() => setScreen('config')}
                disabled={isCapturing}
              >
                <Text style={styles.ghostBtnText}>Config</Text>
              </Pressable>
            </View>
          </View>
        )}

      <StatusBar style="light" />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

/* Tema: fundo noite + laranja “performance” (referência apps esportivos / câmera pro). */
const C = {
  bg: '#070A0F',
  surface: '#0E141C',
  card: '#111824',
  border: 'rgba(255,255,255,0.08)',
  text: '#F1F4FA',
  textMuted: '#8B99AD',
  accent: '#FF6B2C',
  accentDim: 'rgba(255, 107, 44, 0.18)',
  accentBorder: 'rgba(255, 140, 90, 0.45)',
  pillBg: 'rgba(255,255,255,0.05)',
};

const styles = StyleSheet.create({
  safe: {
    flex: 1,
    backgroundColor: C.bg,
  },
  header: {
    paddingBottom: 8,
  },
  headerAccent: {
    height: 3,
    backgroundColor: C.accent,
    marginHorizontal: 16,
    borderRadius: 3,
    opacity: 0.95,
  },
  headerRow: {
    paddingHorizontal: 16,
    paddingTop: 10,
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    gap: 12,
  },
  headerTextCol: {
    flex: 1,
    minWidth: 0,
  },
  eyebrow: {
    color: C.accent,
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 1.2,
    textTransform: 'uppercase',
  },
  title: {
    color: C.text,
    fontSize: 22,
    fontWeight: '800',
    letterSpacing: 0.3,
    marginTop: 2,
  },
  subtitle: {
    color: C.textMuted,
    fontSize: 12,
    marginTop: 4,
    lineHeight: 16,
  },
  subtitleStrong: {
    color: C.text,
    fontWeight: '800',
  },
  badge: {
    backgroundColor: C.surface,
    borderColor: C.border,
    borderWidth: 1,
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 12,
    alignItems: 'flex-end',
    minWidth: 80,
  },
  badgePressed: {
    opacity: 0.88,
  },
  badgeLabel: {
    color: C.textMuted,
    fontSize: 10,
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: 0.4,
  },
  badgeValue: {
    color: C.accent,
    fontSize: 20,
    fontWeight: '800',
    marginTop: 2,
  },
  badgeHint: {
    color: C.textMuted,
    fontSize: 8,
    fontWeight: '600',
    marginTop: 2,
  },
  previewWrap: {
    flex: 1,
    paddingHorizontal: 12,
    paddingBottom: 10,
  },
  preview: {
    flex: 1,
    borderRadius: 20,
    overflow: 'hidden',
    backgroundColor: '#05080C',
    borderWidth: 1,
    borderColor: C.border,
  },
  /** Bloco compacto só enquanto abre o app / falta câmara. Na config com tudo ok não há nada disso. */
  previewStub: {
    marginHorizontal: 12,
    marginBottom: 8,
    padding: 16,
    borderRadius: 16,
    backgroundColor: C.card,
    borderWidth: 1,
    borderColor: C.border,
    alignItems: 'center',
    gap: 10,
  },
  muted: {
    color: C.textMuted,
    textAlign: 'center',
  },
  mutedSmall: {
    color: C.textMuted,
    fontSize: 12,
    lineHeight: 17,
  },
  controls: {
    flex: 1,
  },
  controlsContent: {
    paddingHorizontal: 12,
    paddingBottom: 20,
    gap: 12,
  },
  card: {
    backgroundColor: C.card,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: C.border,
    overflow: 'hidden',
  },
  /** Filete “neon” no topo (laranja do tema — alinhado ao PULSO, com a nossa cor). */
  cardTopAccent: {
    height: 3,
    width: '100%',
    backgroundColor: C.accent,
    opacity: 0.92,
  },
  cardBody: {
    padding: 14,
    gap: 10,
  },
  cardTitle: {
    color: C.text,
    fontSize: 14,
    fontWeight: '800',
    letterSpacing: 0.2,
  },
  cardStat: {
    backgroundColor: C.surface,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: C.border,
    overflow: 'hidden',
  },
  cardStatAccent: {
    height: 2,
    width: '100%',
    backgroundColor: C.accent,
    opacity: 0.88,
  },
  cardStatInner: {
    padding: 12,
  },
  labelInline: {
    color: C.text,
    fontSize: 12,
    fontWeight: '700',
  },
  captureBar: {
    paddingHorizontal: 12,
    paddingBottom: 14,
    paddingTop: 4,
    gap: 8,
  },
  captureMeta: {
    backgroundColor: C.surface,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: C.border,
    overflow: 'hidden',
  },
  captureMetaAccent: {
    height: 2,
    width: '100%',
    backgroundColor: C.accent,
    opacity: 0.88,
  },
  captureMetaInner: {
    padding: 10,
    gap: 6,
  },
  row: {
    gap: 10,
  },
  label: {
    color: C.text,
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 0.2,
  },
  pills: {
    flexDirection: 'row',
    gap: 8,
    flexWrap: 'wrap',
  },
  pill: {
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 999,
    backgroundColor: C.pillBg,
    borderColor: C.border,
    borderWidth: 1,
  },
  pillActive: {
    backgroundColor: C.accentDim,
    borderColor: C.accentBorder,
  },
  pillText: {
    color: C.textMuted,
    fontSize: 12,
    fontWeight: '700',
  },
  pillTextActive: {
    color: C.text,
  },
  errorBox: {
    backgroundColor: 'rgba(255, 60, 60, 0.1)',
    borderColor: 'rgba(255, 100, 100, 0.3)',
    borderWidth: 1,
    borderRadius: 12,
    padding: 12,
    gap: 6,
  },
  errorTitle: {
    color: '#FFB4B4',
    fontWeight: '800',
  },
  errorText: {
    color: '#FFD6D6',
    fontSize: 12,
    lineHeight: 16,
  },
  actions: {
    flexDirection: 'row',
    gap: 10,
    alignItems: 'center',
  },
  actionsSingle: {
    marginTop: 4,
  },
  primaryBtn: {
    flex: 1,
    minWidth: 0,
    height: 52,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: C.accent,
  },
  primaryBtnWide: {
    flexGrow: 0,
    width: '100%',
    flex: undefined,
  },
  primaryBtnText: {
    color: '#0A0A0A',
    fontWeight: '900',
    letterSpacing: 0.4,
    fontSize: 15,
  },
  secondaryBtn: {
    minWidth: 120,
    height: 52,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: C.card,
    borderColor: C.border,
    borderWidth: 1,
  },
  secondaryBtnText: {
    color: C.text,
    fontWeight: '800',
    letterSpacing: 0.2,
  },
  ghostBtn: {
    minWidth: 80,
    height: 52,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'transparent',
    borderColor: C.border,
    borderWidth: 1,
  },
  ghostBtnText: {
    color: C.textMuted,
    fontWeight: '800',
    letterSpacing: 0.2,
    fontSize: 12,
  },
  btnDisabled: {
    opacity: 0.45,
  },
  btnPressed: {
    transform: [{ scale: 0.98 }],
    opacity: 0.92,
  },
});

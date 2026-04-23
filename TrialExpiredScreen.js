import { StatusBar } from 'expo-status-bar';
import { StyleSheet, Text, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';

const C = {
  bg: '#070A0F',
  text: '#F1F4FA',
  muted: '#8B99AD',
  accent: '#FF6B2C',
};

/**
 * Ecrã bloqueado quando `TRIAL_ENDS_AT_ISO` em trialConfig.js já passou.
 */
export function TrialExpiredScreen() {
  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.safe} edges={['top', 'bottom']}>
        <View style={styles.accent} />
        <View style={styles.content}>
          <Text style={styles.eyebrow}>Instant Portrait</Text>
          <Text style={styles.title}>Build de teste expirada</Text>
          <Text style={styles.body}>
            Este APK deixou de estar ativo. Gera uma nova build ou peça acesso a uma versão
            actualizada.
          </Text>
        </View>
        <StatusBar style="light" />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  safe: {
    flex: 1,
    backgroundColor: C.bg,
  },
  accent: {
    height: 3,
    backgroundColor: C.accent,
    marginHorizontal: 16,
    borderRadius: 3,
    marginTop: 8,
  },
  content: {
    flex: 1,
    padding: 24,
    justifyContent: 'center',
    gap: 12,
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
  },
  body: {
    color: C.muted,
    fontSize: 15,
    lineHeight: 22,
  },
});

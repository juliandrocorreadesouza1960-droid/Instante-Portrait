import { registerRootComponent } from 'expo';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import App from './App';
import { isBuildExpired } from './trialConfig';
import { TrialExpiredScreen } from './TrialExpiredScreen';

/** Trial expirado → TrialExpiredScreen; caso contrário App (SafeAreaProvider exige-se por causa de useSafeAreaInsets em App). */
function Root() {
  if (isBuildExpired()) {
    return <TrialExpiredScreen />;
  }
  return (
    <SafeAreaProvider>
      <App />
    </SafeAreaProvider>
  );
}

registerRootComponent(Root);

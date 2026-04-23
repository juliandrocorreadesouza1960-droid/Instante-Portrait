import { registerRootComponent } from 'expo';

import App from './App';
import { isBuildExpired } from './trialConfig';
import { TrialExpiredScreen } from './TrialExpiredScreen';

/**
 * Se o prazo em trialConfig.js tiver acabado, mostra ecrã de término e NÃO monta
 * o resto da app (evita pedir câmara, etc.).
 */
function Root() {
  if (isBuildExpired()) {
    return <TrialExpiredScreen />;
  }
  return <App />;
}

registerRootComponent(Root);

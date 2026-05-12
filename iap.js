/**
 * Camada de billing do AutoFrame (Google Play / App Store) usando
 * `react-native-iap@15` (Nitro/OpenIAP).
 *
 * Esta versão da biblioteca **removeu** as APIs `getSubscriptions` e
 * `requestSubscription` que existiam na v12 — daí o crash "undefined is not
 * a function" no botão do paywall em produção. Aqui usamos a API atual:
 *
 *   - `fetchProducts({ skus, type: 'subs' })`        → carrega ofertas
 *   - `requestPurchase({ request, type: 'subs' })`   → inicia a compra
 *   - `purchaseUpdatedListener` / `purchaseErrorListener` → resultado
 *   - `finishTransaction({ purchase, isConsumable })`→ ACK (obrigatório
 *     no Android — sem isso o Play faz refund automático em 3 dias)
 *
 * Particularidade do Android: o `requestPurchase` exige `subscriptionOffers`
 * com `offerToken` (extraídos do `fetchProducts`). Sem o token a Play SDK
 * recusa com "subscriptionOffers are required for Google Play Subscriptions"
 * — por isso fazemos cache local das ofertas em `_subs` no `iapLoadSubscriptionsAsync`.
 */
import { Platform } from 'react-native';
import {
  endConnection,
  fetchProducts,
  finishTransaction,
  getAvailablePurchases,
  initConnection,
  purchaseErrorListener,
  purchaseUpdatedListener,
  requestPurchase,
} from 'react-native-iap';

export const IAP_SKUS = {
  monthly: 'instante_portrait_premium_monthly',
  yearly: 'instante_portrait_premium_yearly',
};

const SUB_SKUS = [IAP_SKUS.monthly, IAP_SKUS.yearly];

/** Cache local das assinaturas (precisamos guardar o offerToken para Android). */
let _subs = [];

/** Subscriptions dos listeners — limpamos no end para evitar leak entre HMR/relaunch. */
let _purchaseUpdateSub = null;
let _purchaseErrorSub = null;
let _connected = false;

/** Listeners externos (App.js) querem ser notificados quando o user vira premium. */
const _entitlementListeners = new Set();

function _notifyEntitlement(isActive) {
  for (const fn of _entitlementListeners) {
    try {
      fn(Boolean(isActive));
    } catch (e) {
      // Não derruba o pipeline por causa de listener mal-comportado.
      console.warn('[iap] entitlement listener threw:', e?.message ?? e);
    }
  }
}

/**
 * Permite que a UI reaja à mudança de entitlement vinda do
 * `purchaseUpdatedListener` (que é event-based — `requestPurchase`
 * NÃO devolve a compra por promise).
 *
 * @param {(isActive: boolean) => void} listener
 * @returns {() => void} unsubscribe
 */
export function iapOnEntitlementChange(listener) {
  if (typeof listener !== 'function') return () => {};
  _entitlementListeners.add(listener);
  return () => {
    _entitlementListeners.delete(listener);
  };
}

export async function iapInitAsync() {
  if (_connected) return;
  await initConnection();
  _connected = true;

  // Limpa listeners antigos (em HMR) antes de registrar de novo.
  try {
    _purchaseUpdateSub?.remove?.();
  } catch (_) {}
  try {
    _purchaseErrorSub?.remove?.();
  } catch (_) {}

  _purchaseUpdateSub = purchaseUpdatedListener(async (purchase) => {
    try {
      if (purchase?.productId && SUB_SKUS.includes(purchase.productId)) {
        _notifyEntitlement(true);
      }
      // Concluir a transação é obrigatório (ack no Android, finish no iOS).
      // Sem isso, o Play refunds em ~3 dias e o usuário "perde" o premium.
      await finishTransaction({ purchase, isConsumable: false });
    } catch (e) {
      console.warn('[iap] finishTransaction failed:', e?.message ?? e);
    }
  });

  _purchaseErrorSub = purchaseErrorListener((err) => {
    // Cancelamento do usuário é fluxo normal — não logar como erro.
    if (err?.code === 'UserCancelled' || err?.code === 'E_USER_CANCELLED') return;
    console.warn('[iap] purchase error:', err?.code, err?.message);
  });
}

export async function iapEndAsync() {
  try {
    _purchaseUpdateSub?.remove?.();
  } catch (_) {}
  try {
    _purchaseErrorSub?.remove?.();
  } catch (_) {}
  _purchaseUpdateSub = null;
  _purchaseErrorSub = null;

  if (_connected) {
    try {
      await endConnection();
    } catch (_) {}
  }
  _connected = false;
}

/**
 * Carrega as assinaturas configuradas no Google Play / App Store.
 * No Android, é dessa chamada que extraímos `subscriptionOfferDetailsAndroid`
 * (cada oferta tem um `offerToken` obrigatório no `requestPurchase`).
 */
export async function iapLoadSubscriptionsAsync() {
  const list = await fetchProducts({ skus: SUB_SKUS, type: 'subs' });
  _subs = Array.isArray(list) ? list : [];
  return _subs;
}

/**
 * Considera ativo se o Play/App Store devolver pelo menos uma compra ativa
 * com um dos SKUs conhecidos. Para um app simples (sem backend de validação)
 * isso é suficiente — a Play já filtra entitlements expirados.
 */
export async function iapGetActiveEntitlementAsync() {
  try {
    const purchases = await getAvailablePurchases();
    const active =
      Array.isArray(purchases) &&
      purchases.some((p) => p?.productId && SUB_SKUS.includes(p.productId));
    return Boolean(active);
  } catch (e) {
    console.warn('[iap] getAvailablePurchases failed:', e?.message ?? e);
    return false;
  }
}

/**
 * Inicia a compra da assinatura.
 *
 * ⚠️ A v15 do react-native-iap é **event-based**: este método NÃO devolve
 * a compra concluída. O resultado chega no `purchaseUpdatedListener`
 * registrado no `iapInitAsync` (que aciona os `_entitlementListeners`).
 */
export async function iapRequestSubAsync(productId) {
  if (!SUB_SKUS.includes(productId)) {
    throw new Error(`SKU inválido: ${productId}`);
  }

  // No Android precisamos do offerToken — então garantimos que as assinaturas
  // estão em cache antes de pedir a compra.
  if (Platform.OS === 'android' && _subs.length === 0) {
    await iapLoadSubscriptionsAsync();
  }

  if (Platform.OS === 'ios') {
    return await requestPurchase({
      request: { apple: { sku: productId } },
      type: 'subs',
    });
  }

  // Android
  const sub = _subs.find((s) => s?.id === productId || s?.productId === productId);
  // A v15 disponibiliza dois nomes para a mesma coisa (deprecado e novo):
  // - `subscriptionOfferDetailsAndroid` (legado)
  // - `subscriptionOffers` (cross-plat)
  // Usamos o primeiro array não-vazio que encontrarmos.
  const androidOffers = sub?.subscriptionOfferDetailsAndroid ?? sub?.subscriptionOffers;
  if (!Array.isArray(androidOffers) || androidOffers.length === 0) {
    throw new Error(
      `Sem ofertas disponíveis para "${productId}". Verifique o SKU no Play Console.`,
    );
  }

  // Mapeia todas as ofertas — o Billing escolhe a apropriada (base, intro, trial).
  const subscriptionOffers = androidOffers
    .map((o) => ({
      sku: productId,
      offerToken: o?.offerToken,
    }))
    .filter((o) => typeof o.offerToken === 'string' && o.offerToken.length > 0);

  if (subscriptionOffers.length === 0) {
    throw new Error(
      `As ofertas de "${productId}" estão sem offerToken — recarregue o app e tente de novo.`,
    );
  }

  return await requestPurchase({
    request: {
      google: {
        skus: [productId],
        subscriptionOffers,
      },
    },
    type: 'subs',
  });
}

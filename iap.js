import * as RNIap from 'react-native-iap';

export const IAP_SKUS = {
  monthly: 'instante_portrait_premium_monthly',
  yearly: 'instante_portrait_premium_yearly',
};

export async function iapInitAsync() {
  await RNIap.initConnection();
  await RNIap.flushFailedPurchasesCachedAsPendingAndroid?.();
}

export async function iapEndAsync() {
  await RNIap.endConnection();
}

export async function iapLoadSubscriptionsAsync() {
  return await RNIap.getSubscriptions({ skus: [IAP_SKUS.monthly, IAP_SKUS.yearly] });
}

export async function iapGetActiveEntitlementAsync() {
  // Para MVP: considera ativo se existir subscription ativa devolvida pela Play.
  // Em produção ideal: validar purchaseToken no backend.
  const purchases = await RNIap.getAvailablePurchases();
  const active = purchases?.some((p) => p?.productId === IAP_SKUS.monthly || p?.productId === IAP_SKUS.yearly);
  return Boolean(active);
}

export async function iapRequestSubAsync(productId) {
  return await RNIap.requestSubscription({ sku: productId });
}


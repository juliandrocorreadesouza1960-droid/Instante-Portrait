/**
 * Prazo de funcionamento deste APK (ex.: beta com validade).
 *
 * - `null` ou string vazia: sem limite (produção / versão aberta).
 * - Data ISO: após esse instante, a app mostra ecrã de término e não avança.
 *
 * Exemplos (ajustar ANTES de `gradlew assembleRelease`):
 *   '2026-05-10T23:59:59.999-03:00'  // fim do dia em horário de Brasília
 *   '2026-05-10T12:00:00.000Z'     // fim em UTC
 *
 * Nota: quem tiver o APK ainda o pode reinstalar versões antigas; isto é barreira
 * de conveniência para testes, não protecção criptográfica.
 */
/** 30 de abril, 12:00 (horário de Brasília). */
export const TRIAL_ENDS_AT_ISO = '2026-04-30T12:00:00-03:00';

export function isBuildExpired() {
  if (TRIAL_ENDS_AT_ISO == null || String(TRIAL_ENDS_AT_ISO).trim() === '') {
    return false;
  }
  const end = Date.parse(TRIAL_ENDS_AT_ISO);
  if (Number.isNaN(end)) {
    return false;
  }
  return Date.now() > end;
}

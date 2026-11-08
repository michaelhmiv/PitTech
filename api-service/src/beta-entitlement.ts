/**
 * Private-beta all-access (Michael's ruling, 2026-11-08): while PitTech is
 * in private beta, every signed-in user is treated as PitTech Pro, so the
 * whole product can be exercised end to end. The store subscription
 * products are deliberately parked in DRAFT, so no real purchase path can
 * grant Pro to a beta account — without this, nobody can test the Pro
 * surface at all. BETA_ALL_ACCESS=true switches the entitlement reads in
 * subscriptions.ts (the app entitlement route, the community Pro gate and
 * the MCP entitlement lookup all read that one cache) to a beta grant.
 *
 * This is a beta fixture, not a billing path: it never writes entitlement
 * records, and purchase verification, restore and release keep consulting
 * the stores exactly as before. It is reachable only when
 * ONLINE_ENVIRONMENT=staging: setting it in production refuses the boot
 * (see parseBetaAllAccess), the same guard shape as MCP_TEST_PRO_EMAILS.
 * It must be switched off before any public launch where Pro is sold.
 */

/** Parses BETA_ALL_ACCESS. Unset and 'false' mean off. 'true' outside
 * staging is a boot error, not a silent ignore: a leaked variable must
 * never quietly grant Pro in production. Anything else is malformed. */
export function parseBetaAllAccess(raw: string | undefined, environment: 'production' | 'staging'): boolean {
  const value = (raw ?? '').trim().toLowerCase();
  if (value === '' || value === 'false') return false;
  if (value !== 'true') throw new Error('Invalid BETA_ALL_ACCESS');
  if (environment !== 'staging') throw new Error('BETA_ALL_ACCESS is staging-only');
  return true;
}

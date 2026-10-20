import { onlineEnvironment, type OnlineEnvironment } from './online-environment.ts';
import { normalizeCode } from './core.ts';
import type { Pack } from './repository.ts';

export type LinkConfig = { appleAppIds: string[]; androidCertificates: string[]; appleStoreUrl?: string; androidPackageName?: string; online?: OnlineEnvironment };
export function linkConfig(env: NodeJS.ProcessEnv): LinkConfig {
  const online = onlineEnvironment(env.ONLINE_ENVIRONMENT);
  const androidPackageName = env.ANDROID_APP_LINK_PACKAGE ?? (online.environment === 'staging' ? 'com.pittech.dev' : 'com.pittech');
  const allowedPackages = online.environment === 'staging' ? ['com.pittech.dev', 'com.pittech.debug', 'com.pittech'] : ['com.pittech'];
  if (!allowedPackages.includes(androidPackageName)) throw new Error('Invalid Android app association package');
  const appleAppIds = (env.APPLE_APP_IDS ?? '').split(',').map(x => x.trim()).filter(Boolean);
  const androidCertificates = (env.ANDROID_APP_LINK_CERTIFICATES ?? '').split(',').map(x => x.trim()).filter(Boolean);
  if (appleAppIds.some(x => !/^[A-Z0-9]{10}\.com\.pittech(?:\.[a-z]+)?$/.test(x)) ||
      androidCertificates.some(x => !/^(?:[A-F\d]{2}:){31}[A-F\d]{2}$/.test(x))) throw new Error('Invalid app association configuration');
  if (appleAppIds.some(id => online.environment === 'production' ? !/^[A-Z0-9]{10}\.com\.pittech$/.test(id) : !/^[A-Z0-9]{10}\.com\.pittech(?:\.staging)?$/.test(id))) throw new Error('App association environment mismatch');
  const appleStoreUrl = env.APPLE_APP_STORE_URL;
  if (appleStoreUrl && !/^https:\/\/apps\.apple\.com\/[a-z]{2}\/app\/(?:[a-z\d-]+\/)?id\d+$/.test(appleStoreUrl)) throw new Error('Invalid store URL');
  return { appleAppIds, androidCertificates, appleStoreUrl, androidPackageName, online };
}
export function association(path: string, config: LinkConfig) {
  if (path === '/.well-known/apple-app-site-association') return config.appleAppIds.length ? {
    applinks: { details: [{ appIDs: config.appleAppIds, components: [{ '/': '/s/*' }, { '/': '/auth' }, { '/': '/c/*' }, { '/': '/p/*' }] }] },
  } : undefined;
  if (path === '/.well-known/assetlinks.json') return config.androidCertificates.length ? [{
    relation: ['delegate_permission/common.handle_all_urls'],
    target: { namespace: 'android_app', package_name: config.androidPackageName ?? 'com.pittech', sha256_cert_fingerprints: config.androidCertificates },
  }] : undefined;
  return undefined;
}
const escape = (value: string) => value.replace(/[&<>"']/g, x => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[x]!);
export function landing(raw: string, pack: Pack | undefined, now: number, config: LinkConfig) {
  let code: string; try { code = normalizeCode(raw); } catch { code = ''; }
  const available = code !== '' && pack?.code === code && pack.state === 'active' && pack.expiresAt > now;
  const content = available ? `<p class="eyebrow">A COOK PACK FOR YOU</p><h1>Bring this cook into PitTech</h1>
    <p>${pack!.dishes} selected ${pack!.dishes === 1 ? 'dish' : 'dishes'} · ${pack!.kind === 'cook' ? 'Recorded meal' : pack!.kind === 'collection' ? 'Meal collection' : 'Recipe'}</p>
    <p>Preview the pack in the app, then choose exactly what to save. An account is optional.</p>
    <a class="button" href="${config.online?.linkScheme ?? 'pittech'}://share/${code}">Open in PitTech</a>
    <label for="code">Or enter this code in More → Import by code</label><input id="code" readonly value="${code}" aria-label="Cook Pack code">
    <p>Available until ${new Date(pack!.expiresAt).toISOString().slice(0, 10)} UTC. Anyone with this code can access the selected pack.</p>` :
    '<p class="eyebrow">COOK PACK UNAVAILABLE</p><h1>This code is unavailable</h1><p>It may have expired, been revoked, or been entered incorrectly. Check the code or ask the sender for a new share. Your saved recipes remain on your device.</p>';
  return { status: available ? 200 : 404, html: `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex,nofollow"><title>Cook Pack · PitTech</title>
    <style>body{margin:0;background:#f5f0e7;color:#242622;font:1.05rem/1.6 system-ui,sans-serif}main{max-width:38rem;margin:auto;padding:3rem 1.4rem}h1{font-size:clamp(2rem,7vw,3rem);line-height:1.1}.eyebrow{color:#58665a;font-size:.8rem;font-weight:700;letter-spacing:.13em}.button{display:inline-block;background:#a9472a;color:white;padding:.8rem 1.3rem;border-radius:.8rem;text-decoration:none;margin:1rem 0}label{display:block;margin-top:1rem}input{box-sizing:border-box;width:100%;padding:1rem;font:700 clamp(1rem,4vw,1.5rem) monospace;background:#fffdf7;color:#242622;border:1px solid #b8b4a9;border-radius:.7rem}footer{margin-top:2rem;border-top:1px solid #c7c5b9;padding-top:1rem}a{color:#934025}</style></head><body><main><p class="eyebrow">PITTECH</p>${content}<footer><p>Need the app?</p><p><a href="https://play.google.com/store/apps/details?id=com.pittech">Google Play</a>${config.appleStoreUrl ? ` · <a href="${escape(config.appleStoreUrl)}">App Store</a>` : ' · iOS availability will be announced here.'}</p><p>After installation, reopen this page or enter the code manually. Installation does not automatically import a pack.</p><p>No download, account creation, or import happens on this page.</p></footer></main></body></html>` };
}

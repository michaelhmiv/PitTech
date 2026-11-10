import { readFile } from 'node:fs/promises';
// The public landing page served at GET / on this service (the apex
// pittech.app and the API origin alike, like /auth). The page is the
// owner-picked design ("The Log") ported verbatim except for asset paths;
// its files live under web/landing* and are read once at boot, following
// the brand-web.ts / lifecycle-web.ts pattern. The asset table is a fixed
// allowlist — request paths are only ever looked up, never resolved.
const page = await readFile(new URL('../web/landing.html', import.meta.url), 'utf8');
export function landingPage() { return page; }

export type LandingAsset = { contentType: string; body: Buffer };
const manifest: ReadonlyArray<readonly [route: string, file: string, contentType: string]> = [
  ['/assets/app-icon.png', 'landing/app-icon.png', 'image/png'],
  ['/screenshots/live-board.png', 'landing/live-board.png', 'image/png'],
  ['/screenshots/timeline.png', 'landing/timeline.png', 'image/png'],
  ['/screenshots/history.png', 'landing/history.png', 'image/png'],
  ['/assets/fonts/oswald-500.ttf', 'landing/fonts/oswald-500.ttf', 'font/ttf'],
  ['/assets/fonts/oswald-600.ttf', 'landing/fonts/oswald-600.ttf', 'font/ttf'],
  ['/assets/fonts/oswald-700.ttf', 'landing/fonts/oswald-700.ttf', 'font/ttf'],
  ['/assets/fonts/barlow-400.ttf', 'landing/fonts/barlow-400.ttf', 'font/ttf'],
  ['/assets/fonts/barlow-500.ttf', 'landing/fonts/barlow-500.ttf', 'font/ttf'],
  ['/assets/fonts/barlow-600.ttf', 'landing/fonts/barlow-600.ttf', 'font/ttf'],
  ['/assets/fonts/barlow-700.ttf', 'landing/fonts/barlow-700.ttf', 'font/ttf'],
  ['/assets/fonts/barlow-800.ttf', 'landing/fonts/barlow-800.ttf', 'font/ttf'],
  ['/assets/fonts/ibm-plex-mono-400.ttf', 'landing/fonts/ibm-plex-mono-400.ttf', 'font/ttf'],
  ['/assets/fonts/ibm-plex-mono-500.ttf', 'landing/fonts/ibm-plex-mono-500.ttf', 'font/ttf'],
  ['/assets/fonts/ibm-plex-mono-600.ttf', 'landing/fonts/ibm-plex-mono-600.ttf', 'font/ttf'],
];
const assets = new Map<string, LandingAsset>();
for (const [route, file, contentType] of manifest) {
  assets.set(route, { contentType, body: await readFile(new URL('../web/' + file, import.meta.url)) });
}
export function landingAsset(path: string): LandingAsset | undefined { return assets.get(path); }

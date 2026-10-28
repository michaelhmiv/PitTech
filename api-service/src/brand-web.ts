import { readFile } from 'node:fs/promises';
// The Ember mascot served at /brand/ember-logo.png on the API origin, so
// transactional email can brand itself from the same first-party origin that
// sends it (see email-shell.ts). Kept small: 168x168 PNG, displayed at 48px.
const logo = await readFile(new URL('../web/ember-logo.png', import.meta.url));
export function emberLogoPng() { return logo; }

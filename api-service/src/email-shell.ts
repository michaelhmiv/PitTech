// Shared TURN-IN shell for PitTech transactional email (sign-in + lifecycle notices).
// Table layout and inline CSS only: no webfonts and no external stylesheets, so the
// mail renders the same in clients that strip <style>. The dark palette is declared
// through color-scheme and every surface carries an explicit background color, so
// clients keep these colors instead of auto-inverting them into low contrast.
// Title and body arguments must already be HTML-escaped by the caller.
export const emailColors = { ground: '#10151C', card: '#1D2733', cell: '#27313F', line: '#33404F', cream: '#F5EFE3', muted: '#9AA7B4', ember: '#F07D2E', ink: '#1D2733', cobalt: '#6E8FE8' } as const;
const displayFont = `'Arial Narrow','HelveticaNeue-CondensedBold','Roboto Condensed',Arial,sans-serif`;
const textFont = `Arial,Helvetica,sans-serif`;
export const emailHeading = (value: string) => `<h1 style="margin:0 0 16px;font:700 27px/1.25 ${displayFont};letter-spacing:.5px;color:${emailColors.cream}">${value}</h1>`;
export const emailMuted = (value: string) => `<p style="margin:16px 0 0;font:15px/1.6 ${textFont};color:${emailColors.muted}">${value}</p>`;
export const emailButton = (href: string, label: string) => `<table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr><td align="center" bgcolor="${emailColors.ember}" style="border-radius:8px;background-color:${emailColors.ember}"><a href="${href}" target="_blank" style="display:inline-block;padding:13px 26px;font:700 17px/1.35 ${textFont};color:${emailColors.ink};text-decoration:none">${label}</a></td></tr></table>`;
export function emailShell(origin: string, title: string, body: string) {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><meta name="supported-color-schemes" content="dark"><title>${title}</title></head>`
    + `<body style="margin:0;padding:0;background-color:${emailColors.ground}">`
    + `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="${emailColors.ground}" style="background-color:${emailColors.ground}"><tr><td align="center" style="padding:32px 16px">`
    + `<table role="presentation" width="560" cellpadding="0" cellspacing="0" border="0" bgcolor="${emailColors.card}" style="width:100%;max-width:560px;background-color:${emailColors.card};border:1px solid ${emailColors.line};border-radius:12px">`
    + `<tr><td style="padding:24px 32px 18px;border-bottom:1px solid ${emailColors.line}"><table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>`
    + `<td style="padding-right:14px"><img src="${origin}/brand/ember-logo.png" width="48" height="48" alt="PitTech" style="display:block;border:0;border-radius:10px"></td>`
    + `<td style="font:700 22px/1 ${displayFont};letter-spacing:3px;color:${emailColors.cream}">PITTECH</td>`
    + `</tr></table></td></tr>`
    + `<tr><td style="padding:26px 32px 30px;font:17px/1.6 ${textFont};color:${emailColors.cream}">${body}</td></tr>`
    + `</table></td></tr></table></body></html>`;
}

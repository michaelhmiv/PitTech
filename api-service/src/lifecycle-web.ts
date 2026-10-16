import { readFile } from 'node:fs/promises';
const template=await readFile(new URL('../web/account-deletion.html',import.meta.url),'utf8');
const operatorTemplate=await readFile(new URL('../web/operator.html',import.meta.url),'utf8');
export function deletionPage(nonce:string){if(!/^[a-f0-9]{32}$/.test(nonce))throw new Error('Invalid page nonce');return template.replaceAll('__NONCE__',nonce);}
export function operatorPage(nonce:string){if(!/^[a-f0-9]{32}$/.test(nonce))throw new Error('Invalid page nonce');return operatorTemplate.replaceAll('__NONCE__',nonce);}

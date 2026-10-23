import { onlineEnvironment } from './online-environment.ts';
import { CommunityLifecycle } from './community-lifecycle.ts';
import { AccountDeletion } from './account-deletion.ts';
import { PrivateSyncPurger } from './sync-purge.ts';
import { ResendLifecycleMailer } from './lifecycle-email.ts';
import { Publications } from './publications.ts';
import { Authentication } from './auth.ts';
import { AuthSecrets } from './auth-credentials.ts';
import { ResendAuthMailer } from './auth-email.ts';
import { Pool } from 'pg';
import { S3Client } from '@aws-sdk/client-s3';
import { FileStorage, S3Storage, type Storage } from './storage.ts';
import { PostgresRepository } from './repository.ts';
import { Relay } from './relay.ts';
import { linkConfig } from './web.ts';
import { Subscriptions, PostgresBillingStore } from './subscriptions.ts';
import { AppleStoreApi, type AppleTrust } from './subscriptions-apple.ts';
import { PlayDeveloperApi } from './subscriptions-google.ts';
import { SyncEncryption } from './sync-encryption.ts';
import { PrivateSnapshots, type SyncStorageLimits } from './sync-snapshots.ts';
import { PrivatePhotos } from './sync-photos.ts';
import { privateLibraryEnvelope } from './sync-projection.ts';
import { CommunityAggregates, PostgresCommunityStore } from './community-aggregation.ts';
import { McpGrants, PostgresMcpGrantStore, SubscriptionsEntitlementLookup } from './mcp-grants.ts';
import { McpOAuth, PostgresMcpOAuthStore, type McpOAuthClient } from './mcp-oauth.ts';
import { PrivateLibraryService } from './mcp-library.ts';
import { McpServer } from './mcp-server.ts';
import { APPLE_KEYS_ENDPOINT, GOOGLE_CERTS_ENDPOINT, GoogleOidcKeyCache } from './google-oidc-keys.ts';
import type { ProviderServices } from './auth-providers.ts';

const required = (name: string) => { const value = process.env[name]; if (!value) throw new Error('Missing configuration'); return value; };
function positive(name: string) { const n = Number(required(name)); if (!Number.isSafeInteger(n) || n <= 0) throw new Error('Invalid limit'); return n; }
function positiveDefault(name: string, fallback: number) { const raw = process.env[name]; if (raw === undefined) return fallback; const n = Number(raw); if (!Number.isSafeInteger(n) || n <= 0) throw new Error('Invalid limit'); return n; }
function appleAudiences(): string[] | undefined {
  const raw = process.env.AUTH_APPLE_AUDIENCES;
  if (raw === undefined || raw === '') return undefined;
  const audiences = [...new Set(raw.split(',').map(entry => entry.trim()))];
  if (!audiences.length || audiences.some(entry => entry.length > 255 || !/^[A-Za-z0-9]+([.-][A-Za-z0-9]+)*$/.test(entry))) throw new Error('Invalid provider configuration');
  return audiences;
}
function googleWebClientId(): string | undefined {
  const raw = process.env.AUTH_GOOGLE_WEB_CLIENT_ID;
  if (raw === undefined || raw === '') return undefined;
  if (raw.length > 256 || !/^[A-Za-z0-9-]+\.apps\.googleusercontent\.com$/.test(raw)) throw new Error('Invalid provider configuration');
  return raw;
}
function appleChainRoots(): string[] {
  let roots: unknown;
  try { roots = JSON.parse(required('PRO_APPLE_ROOTS')); } catch { throw new Error('Invalid Apple trust configuration'); }
  if (!Array.isArray(roots) || !roots.length || !roots.every(root => typeof root === 'string')) throw new Error('Invalid Apple trust configuration');
  return roots;
}
export function createRuntime() {
  const online = onlineEnvironment(process.env.ONLINE_ENVIRONMENT);
  if (process.env.AUTH_ENABLED === 'true' && process.env.AUTH_PUBLIC_ORIGIN !== online.publicOrigin) throw new Error('Authentication origin does not match online environment');
  const ipKey = required('RATE_HMAC_KEY'); if (ipKey.length < 32) throw new Error('Invalid HMAC key');
  const url = required('DATABASE_URL'); if (!['postgres:', 'postgresql:'].includes(new URL(url).protocol)) throw new Error('Invalid database URL');
  const budgets = { storageBytes: positive('STORAGE_MAX_BYTES'), dailyBytes: positive('DAILY_UPLOAD_MAX_BYTES'), dailyUploads: positive('DAILY_UPLOAD_MAX_COUNT') };
  let storage: Storage;
  if (required('OBJECT_STORAGE') === 'filesystem') {
    if (process.env.NODE_ENV === 'production') throw new Error('Private object storage required');
    storage = new FileStorage(required('OBJECT_DIRECTORY'));
  } else {
    if (process.env.OBJECT_STORAGE !== 's3' || new URL(required('S3_ENDPOINT')).protocol !== 'https:') throw new Error('Invalid storage configuration');
    const client = new S3Client({ endpoint: required('S3_ENDPOINT'), region: required('S3_REGION'), forcePathStyle: false, maxAttempts: 2,
      credentials: { accessKeyId: required('S3_ACCESS_KEY_ID'), secretAccessKey: required('S3_SECRET_ACCESS_KEY') } });
    storage = new S3Storage(client, required('S3_BUCKET'));
  }
  const pool = new Pool({ connectionString: url, max: 5, connectionTimeoutMillis: 5000, idleTimeoutMillis: 30000, statement_timeout: 10000 });
  pool.on('error', () => console.error('database_connection_error'));
  const relay = new Relay(new PostgresRepository(pool), storage, budgets);
  const port = Number(process.env.PORT ?? 3000); if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid port');
  const authSecrets=process.env.AUTH_ENABLED==='true'?new AuthSecrets(required('AUTH_HASH_KEY'),required('AUTH_ENCRYPTION_KEY')):undefined;
  // Provider sign-in rides AUTH_ENABLED: audiences and the Google web client
  // come from deployment config, and email-only deployments set neither.
  const appleAudienceList=appleAudiences(),googleClientId=googleWebClientId();
  if((appleAudienceList||googleClientId)&&!authSecrets)throw new Error('Accounts required');
  const lifecycleEnabled=process.env.ACCOUNT_LIFECYCLE_ENABLED==='true';
  if(lifecycleEnabled&&!authSecrets)throw new Error('Accounts required');
  const lifecycleUrl=lifecycleEnabled?required('LIFECYCLE_DATABASE_URL'):undefined;
  if(lifecycleUrl&&!['postgres:','postgresql:'].includes(new URL(lifecycleUrl).protocol))throw new Error('Invalid lifecycle database URL');
  const lifecycleSecrets=lifecycleEnabled?new AuthSecrets(required('LIFECYCLE_HASH_KEY'),required('LIFECYCLE_ENCRYPTION_KEY')):undefined;
  if(lifecycleSecrets&&(process.env.LIFECYCLE_HASH_KEY===process.env.AUTH_HASH_KEY||process.env.LIFECYCLE_ENCRYPTION_KEY===process.env.AUTH_ENCRYPTION_KEY))throw new Error('Separate lifecycle keys required');
  const lifecyclePool=lifecycleUrl?new Pool({connectionString:lifecycleUrl,max:5,connectionTimeoutMillis:5000,idleTimeoutMillis:30000,statement_timeout:10000}):undefined;
  lifecyclePool?.on('error',()=>console.error('lifecycle_database_connection_error'));
  const community=lifecyclePool&&lifecycleSecrets?new CommunityLifecycle(lifecyclePool,lifecycleSecrets,Date.now,storage):undefined;
  const deletion=lifecyclePool&&lifecycleSecrets?new AccountDeletion(lifecyclePool,lifecycleSecrets,storage,new ResendLifecycleMailer(required('RESEND_API_KEY'),required('AUTH_FROM'),required('AUTH_PUBLIC_ORIGIN')),email=>authSecrets!.hash('email',email),Date.now,new PrivateSyncPurger(lifecyclePool,storage)):undefined;
  let refreshProviderKeys:(() => Promise<void>)|undefined,providers:ProviderServices|undefined;
  if(appleAudienceList||googleClientId){
    const appleKeys=new GoogleOidcKeyCache(fetch,Date.now,APPLE_KEYS_ENDPOINT),googleKeys=new GoogleOidcKeyCache(fetch,Date.now,GOOGLE_CERTS_ENDPOINT);
    refreshProviderKeys=async()=>{if(appleAudienceList)await appleKeys.refresh();if(googleClientId)await googleKeys.refresh();};
    const next:ProviderServices={};
    if(appleAudienceList)next.apple={audiences:appleAudienceList,keys:appleKeys,trust:{mode:'chain',roots:appleChainRoots()}};
    if(googleClientId)next.google={webClientId:googleClientId,keys:googleKeys};
    if(deletion)next.deletion=deletion;
    providers=next;
  }
  const auth = authSecrets ? new Authentication(pool,
    authSecrets,
    new ResendAuthMailer(required('RESEND_API_KEY'), required('AUTH_FROM'), required('AUTH_PUBLIC_ORIGIN')), positive('AUTH_DAILY_EMAIL_LIMIT'), Date.now, providers) : undefined;
  if(process.env.PUBLIC_API_ENABLED==='true'&&(!auth||!community||!deletion||process.env.PUBLIC_MODERATION_READY!=='true'||process.env.ACCOUNT_DELETION_READY!=='true'))throw new Error('Complete phase required');
  if(process.env.PUBLIC_API_ENABLED==='true'&&budgets.storageBytes+positive('PUBLIC_STORAGE_MAX_BYTES')>positive('TOTAL_OBJECT_STORAGE_MAX_BYTES'))throw new Error('Combined storage budget exceeds cap');
  const publications=process.env.PUBLIC_API_ENABLED==='true'?new Publications(pool,storage,{storageBytes:positive('PUBLIC_STORAGE_MAX_BYTES'),dailyBytes:positive('PUBLIC_DAILY_BYTES'),dailyWrites:positive('PUBLIC_DAILY_WRITES')},Date.now,true,handle=>lifecycleSecrets!.hash('retired-handle',handle),online):undefined;
  const syncEnabled=process.env.PRIVATE_SYNC_ENABLED==='true';
  if(syncEnabled&&!authSecrets)throw new Error('Accounts required');
  const syncUrl=syncEnabled?required('PRIVATE_SYNC_DATABASE_URL'):undefined;
  if(syncUrl&&!['postgres:','postgresql:'].includes(new URL(syncUrl).protocol))throw new Error('Invalid private sync database URL');
  const syncPool=syncUrl?new Pool({connectionString:syncUrl,max:5,connectionTimeoutMillis:5000,idleTimeoutMillis:30000,statement_timeout:10000}):undefined;
  syncPool?.on('error',()=>console.error('sync_database_connection_error'));
  let syncKeys:Record<string,string>|undefined;
  if(syncEnabled){
    const raw=required('PRIVATE_SYNC_ENCRYPTION_KEYS');
    let parsed:unknown; try{parsed=JSON.parse(raw);}catch{throw new Error('Invalid private sync encryption configuration');}
    if(!parsed||typeof parsed!=='object'||Array.isArray(parsed)||!Object.values(parsed).every(v=>typeof v==='string'))throw new Error('Invalid private sync encryption configuration');
    syncKeys=parsed as Record<string,string>;
  }
  if(syncKeys&&Object.values(syncKeys).some(k=>[process.env.AUTH_HASH_KEY,process.env.AUTH_ENCRYPTION_KEY,process.env.LIFECYCLE_HASH_KEY,process.env.LIFECYCLE_ENCRYPTION_KEY].includes(k)))throw new Error('Separate private sync keys required');
  const syncEncryption=syncKeys?new SyncEncryption(required('PRIVATE_SYNC_CURRENT_KEY_ID'),syncKeys):undefined;
  // One shared limits object serves snapshots and photos, so the defaults follow the
  // photo-capable values from the sync storage tests rather than the small snapshot-only ones.
  const syncLimits:SyncStorageLimits|undefined=syncEnabled?{ownerBytes:positiveDefault('PRIVATE_SYNC_LIMIT_OWNER_BYTES',64*1024**2),globalBytes:positiveDefault('PRIVATE_SYNC_LIMIT_GLOBAL_BYTES',128*1024**2),dailyBytes:positiveDefault('PRIVATE_SYNC_LIMIT_DAILY_BYTES',256*1024**2),dailyUploads:positiveDefault('PRIVATE_SYNC_LIMIT_DAILY_UPLOADS',200),ownerOperations:positiveDefault('PRIVATE_SYNC_LIMIT_OWNER_OPERATIONS',100)}:undefined;
  const sync=syncPool&&syncEncryption&&syncLimits?{snapshots:new PrivateSnapshots(syncPool,storage,syncEncryption,privateLibraryEnvelope,syncLimits),photos:new PrivatePhotos(syncPool,storage,syncEncryption,syncLimits)}:undefined;
  // Subscriptions stay disabled until store products, sandbox evidence and the
  // paywall inventory land; enabling requires a verified account backend.
  const billingEnabled=process.env.SUBSCRIPTIONS_ENABLED==='true';
  if(billingEnabled&&!authSecrets)throw new Error('Accounts required');
  let subscriptions:Subscriptions|undefined,refreshOidcKeys:(() => Promise<void>)|undefined;
  if(billingEnabled){
    const appleConfig={bundleId:required('PRO_APPLE_BUNDLE_ID'),productId:required('PRO_APPLE_PRODUCT_ID')};
    const googleConfig={packageName:required('PRO_GOOGLE_PACKAGE'),productId:required('PRO_GOOGLE_PRODUCT_ID')};
    const trustMode=process.env.PRO_APPLE_TRUST_MODE??'chain';
    let trust:AppleTrust;
    if(trustMode==='pinned'){
      let parsed:unknown;try{parsed=JSON.parse(required('PRO_APPLE_PINNED_KEYS'));}catch{throw new Error('Invalid Apple trust configuration');}
      if(!parsed||typeof parsed!=='object'||Array.isArray(parsed)||!Object.values(parsed).every(v=>typeof v==='string'))throw new Error('Invalid Apple trust configuration');
      trust={mode:'pinned',keys:new Map(Object.entries(parsed as Record<string,string>))};
    }else if(trustMode==='chain'){
      trust={mode:'chain',roots:appleChainRoots()};
    }else throw new Error('Invalid Apple trust configuration');
    const storeJson=async(response:Response)=>{try{return await response.json();}catch{return undefined;}};
    const appleFetch=async(url:string,token:string)=>{
      if(!url.startsWith('https://api.storekit.'))throw new Error('Invalid store host');
      const response=await fetch(url,{headers:{Authorization:`Bearer ${token}`},signal:AbortSignal.timeout(10000)});
      return {status:response.status,body:await storeJson(response)};
    };
    const appleApi=new AppleStoreApi({issuerId:required('PRO_APPLE_ISSUER_ID'),keyId:required('PRO_APPLE_KEY_ID'),privateKey:required('PRO_APPLE_PRIVATE_KEY').replace(/\\n/g,'\n'),bundleId:appleConfig.bundleId},appleFetch);
    let serviceAccount:unknown;try{serviceAccount=JSON.parse(required('PRO_GOOGLE_SERVICE_ACCOUNT'));}catch{throw new Error('Invalid Play service configuration');}
    const account=serviceAccount as Record<string,unknown>;
    if(typeof account.client_email!=='string'||typeof account.private_key!=='string')throw new Error('Invalid Play service configuration');
    const playFetch=async(url:string,init:{method:string;token?:string;body?:string;contentType?:string})=>{
      if(!url.startsWith('https://oauth2.googleapis.com/')&&!url.startsWith('https://androidpublisher.googleapis.com/'))throw new Error('Invalid store host');
      const headers:Record<string,string>={};if(init.token)headers.Authorization=`Bearer ${init.token}`;if(init.contentType)headers['Content-Type']=init.contentType;
      const response=await fetch(url,{method:init.method,headers,body:init.body,signal:AbortSignal.timeout(10000)});
      return {status:response.status,body:await storeJson(response)};
    };
    const playApi=new PlayDeveloperApi({clientEmail:account.client_email,privateKey:(account.private_key as string).replace(/\\n/g,'\n')},playFetch);
    const oidcKeys=new GoogleOidcKeyCache(),oidcAudience=required('PRO_GOOGLE_RTDN_AUDIENCE');
    const oidcEmail=required('PRO_GOOGLE_RTDN_SERVICE_ACCOUNT_EMAIL');
    if(!/^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.gserviceaccount\.com$/.test(oidcEmail))throw new Error('Invalid Pub/Sub push identity');
    refreshOidcKeys=()=>oidcKeys.refresh();
    subscriptions=new Subscriptions(new PostgresBillingStore(pool),{apple:{config:appleConfig,trust,api:appleApi},google:{config:googleConfig,api:playApi,oidcKeys,oidcAudience,oidcEmail}});
  }
  // Community aggregation stays off unless explicitly enabled. When on, the job
  // persists via the isolated aggregation tables through the allowlisted
  // projection only (least-privilege runtime role in production).
  // MCP (issue #114, M1) rides the verified account stack: enabling it
  // requires accounts, subscriptions (the Pro entitlement it checks on every
  // call) and private sync (the library it will serve). The grants service
  // is constructed only when enabled; writes stay a separate, later gate.
  const mcpEnabled=process.env.MCP_ENABLED==='true';
  if(mcpEnabled&&(!auth||!subscriptions||!sync))throw new Error('MCP requires accounts, subscriptions and private sync');
  const mcpWritesEnabled=mcpEnabled&&process.env.MCP_WRITES_ENABLED==='true';
  const mcp=mcpEnabled?new McpGrants(new PostgresMcpGrantStore(pool),authSecrets!,new SubscriptionsEntitlementLookup(subscriptions!),Date.now):undefined;
  // OAuth clients are deployment configuration only: no dynamic client
  // registration exists, and a malformed registry fails the boot rather
  // than authorizing an unintended client. No clients configured → the
  // OAuth surface stays unmounted and PAT remains the connection path.
  let oauthClients: McpOAuthClient[] = [];
  if (process.env.MCP_OAUTH_CLIENTS) {
    const parsed: unknown = JSON.parse(process.env.MCP_OAUTH_CLIENTS);
    if (!Array.isArray(parsed) || parsed.some(c => !c || typeof c !== 'object' || typeof (c as { id?: unknown }).id !== 'string' || typeof (c as { name?: unknown }).name !== 'string' || !Array.isArray((c as { redirectUris?: unknown }).redirectUris) || ((c as { redirectUris: unknown[] }).redirectUris).some(u => typeof u !== 'string' || !u.startsWith('https://')))) throw new Error('Invalid MCP_OAUTH_CLIENTS');
    oauthClients = parsed as McpOAuthClient[];
    if (new Set(oauthClients.map(c => c.id)).size !== oauthClients.length) throw new Error('Invalid MCP_OAUTH_CLIENTS');
  }
  if (oauthClients.length && !mcp) throw new Error('MCP OAuth clients require MCP_ENABLED');
  const oauth=mcp&&oauthClients.length?new McpOAuth(new PostgresMcpOAuthStore(pool),mcp,authSecrets!,oauthClients,Date.now):undefined;
  const mcpServer=mcp&&sync?new McpServer(new PrivateLibraryService(mcp,sync.snapshots,{writer:sync.snapshots,writesEnabled:mcpWritesEnabled,photos:{begin:(user,input)=>sync.photos.begin(user,input),statusOf:(user,asset)=>sync.photos.statusOf(user,asset),putPart:(user,asset,index,bytes)=>sync.photos.putPart(user,asset,index,bytes),finish:(user,asset)=>sync.photos.finish(user,asset),read:(user,asset)=>sync.photos.read(user,asset),abandon:(user,asset)=>sync.photos.abandon(user,asset,sync.snapshots)}})):undefined;
  const communityAggregationEnabled=process.env.COMMUNITY_AGGREGATION_ENABLED==='true';
  const communityAggregates=communityAggregationEnabled?new CommunityAggregates(new PostgresCommunityStore(pool),Date.now):undefined;
  return { pool, lifecyclePool, syncPool, relay, port, auth, publications, community, deletion, sync, subscriptions, mcp, oauth, mcpServer, refreshOidcKeys, refreshProviderKeys, communityAggregates, close:async()=>{await Promise.all([pool.end(),lifecyclePool?.end(),syncPool?.end()]);}, options: { ipKey, auth, publications, community, deletion, sync, subscriptions, mcp, oauth, mcpServer, oauthIssuer: process.env.AUTH_PUBLIC_ORIGIN, mcpWritesEnabled, communityAggregates, links: linkConfig(process.env), uploadsEnabled: process.env.RELAY_UPLOADS_ENABLED === 'true',
    trustedProxies: (process.env.TRUSTED_PROXY_CIDRS ?? '').split(',').map(s => s.trim()).filter(Boolean) } };
}

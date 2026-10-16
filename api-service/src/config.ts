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

const required = (name: string) => { const value = process.env[name]; if (!value) throw new Error('Missing configuration'); return value; };
function positive(name: string) { const n = Number(required(name)); if (!Number.isSafeInteger(n) || n <= 0) throw new Error('Invalid limit'); return n; }
export function createRuntime() {
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
  const auth = authSecrets ? new Authentication(pool,
    authSecrets,
    new ResendAuthMailer(required('RESEND_API_KEY'), required('AUTH_FROM'), required('AUTH_PUBLIC_ORIGIN')), positive('AUTH_DAILY_EMAIL_LIMIT')) : undefined;
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
  if(process.env.PUBLIC_API_ENABLED==='true'&&(!auth||!community||!deletion||process.env.PUBLIC_MODERATION_READY!=='true'||process.env.ACCOUNT_DELETION_READY!=='true'))throw new Error('Complete phase required');
  if(process.env.PUBLIC_API_ENABLED==='true'&&budgets.storageBytes+positive('PUBLIC_STORAGE_MAX_BYTES')>positive('TOTAL_OBJECT_STORAGE_MAX_BYTES'))throw new Error('Combined storage budget exceeds cap');
  const publications=process.env.PUBLIC_API_ENABLED==='true'?new Publications(pool,storage,{storageBytes:positive('PUBLIC_STORAGE_MAX_BYTES'),dailyBytes:positive('PUBLIC_DAILY_BYTES'),dailyWrites:positive('PUBLIC_DAILY_WRITES')},Date.now,true,handle=>lifecycleSecrets!.hash('retired-handle',handle)):undefined;
  return { pool, lifecyclePool, relay, port, auth, publications, community, deletion, close:async()=>{await Promise.all([pool.end(),lifecyclePool?.end()]);}, options: { ipKey, auth, publications, community, deletion, links: linkConfig(process.env), uploadsEnabled: process.env.RELAY_UPLOADS_ENABLED === 'true',
    trustedProxies: (process.env.TRUSTED_PROXY_CIDRS ?? '').split(',').map(s => s.trim()).filter(Boolean) } };
}

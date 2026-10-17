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
    const client = new S3Client({ endpoint: required('S3_ENDPOINT'), region: required('S3_REGION'), forcePathStyle: true, maxAttempts: 2,
      credentials: { accessKeyId: required('S3_ACCESS_KEY_ID'), secretAccessKey: required('S3_SECRET_ACCESS_KEY') } });
    storage = new S3Storage(client, required('S3_BUCKET'));
  }
  const pool = new Pool({ connectionString: url, max: 5, connectionTimeoutMillis: 5000, idleTimeoutMillis: 30000, statement_timeout: 10000 });
  pool.on('error', () => console.error('database_connection_error'));
  const relay = new Relay(new PostgresRepository(pool), storage, budgets);
  const port = Number(process.env.PORT ?? 3000); if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid port');
  const auth = process.env.AUTH_ENABLED === 'true' ? new Authentication(pool,
    new AuthSecrets(required('AUTH_HASH_KEY'), required('AUTH_ENCRYPTION_KEY')),
    new ResendAuthMailer(required('RESEND_API_KEY'), required('AUTH_FROM'), required('AUTH_PUBLIC_ORIGIN')), positive('AUTH_DAILY_EMAIL_LIMIT')) : undefined;
  return { pool, relay, port, auth, options: { ipKey, auth, links: linkConfig(process.env), uploadsEnabled: process.env.RELAY_UPLOADS_ENABLED === 'true',
    trustedProxies: (process.env.TRUSTED_PROXY_CIDRS ?? '').split(',').map(s => s.trim()).filter(Boolean) } };
}

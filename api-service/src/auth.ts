import { randomUUID } from 'node:crypto';
import type { Pool, PoolClient } from 'pg';
import { ApiError } from './core.ts';
import { lockAccount } from './lifecycle-contract.ts';
import { AUTH, AuthSecrets, credential, emailAddress, equalHash, isCredential, proofHash } from './auth-credentials.ts';
import type { AuthMailer, SignInMail } from './auth-email.ts';

const uuid = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(value);
const unavailable = () => new ApiError(401, 'sign_in_unavailable');
type Challenge = { id: string; email: string; email_key: string; proof_hash: string; code_hash: string; link_hash: string; created_at: string; expires_at: string; attempts: number; consumed_at: string | null; exchange_id: string | null; exchange_envelope: string | null; exchange_expires_at: string | null };
export type AuthSession = { accessToken: string; refreshToken: string; accessExpiresAt: string; sessionExpiresAt: string; userId: string };
export class Authentication {
  private pool: Pool; private secrets: AuthSecrets; private mailer: AuthMailer; private dailyEmails: number; clock: () => number;
  constructor(pool: Pool, secrets: AuthSecrets, mailer: AuthMailer, dailyEmails: number, clock = Date.now) {
    if (!Number.isSafeInteger(dailyEmails) || dailyEmails < 1) throw new Error('Auth email budget required');
    this.pool = pool; this.secrets = secrets; this.mailer = mailer; this.dailyEmails = dailyEmails; this.clock = clock;
  }
  private async transaction<T>(body: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try { await client.query('BEGIN'); const value = await body(client); await client.query('COMMIT'); return value; }
    catch (error) { await client.query('ROLLBACK'); throw error; } finally { client.release(); }
  }
  private async rate(client: PoolClient, key: string, maximum: number, expires: number) {
    return !!(await client.query('INSERT INTO auth.rate_buckets VALUES($1,1,$2) ON CONFLICT(key) DO UPDATE SET count=auth.rate_buckets.count+1 WHERE auth.rate_buckets.count<$3 RETURNING count', [key, expires, maximum])).rowCount;
  }
  async start(input: { email: unknown; idempotencyId: unknown; proofChallenge: unknown }) {
    const email = emailAddress(input.email), id = input.idempotencyId, proof = input.proofChallenge;
    if (!uuid(id) || typeof proof !== 'string' || !/^[a-f0-9]{64}$/.test(proof)) throw new ApiError(400, 'sign_in_request_invalid');
    const emailKey = this.secrets.hash('email', email), now = this.clock(), day = Math.floor(now / 86400_000);
    await this.transaction(async client => {
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,890008))', [emailKey]);
      const prior = (await client.query<Challenge>('SELECT * FROM auth.challenges WHERE id=$1', [id])).rows[0];
      if (prior) {
        if (prior.email_key !== emailKey || !equalHash(prior.proof_hash, proof) || prior.consumed_at != null || Number(prior.expires_at) <= now) throw new ApiError(409, 'sign_in_request_unavailable');
        return;
      }
      if ((await client.query('SELECT 1 FROM auth.users u JOIN auth.account_lifecycle a ON a.user_id=u.id WHERE u.email=$1 AND a.deleting', [email])).rowCount) return;
      const latest = (await client.query('SELECT created_at FROM auth.challenges WHERE email_key=$1 ORDER BY created_at DESC LIMIT 1', [emailKey])).rows[0];
      if (latest && now - Number(latest.created_at) < AUTH.resendMs) throw new ApiError(429, 'rate_limited');
      if (!await this.rate(client, `email:${emailKey}:${day}`, 10, (day + 1) * 86400_000) || !await this.rate(client, `global:${day}`, this.dailyEmails, (day + 1) * 86400_000)) throw new ApiError(429, 'rate_limited');
      const mail: SignInMail = { email, challengeId: id, code: this.secrets.code(), token: credential() };
      await client.query('UPDATE auth.challenges SET consumed_at=$2,mail_envelope=NULL WHERE email_key=$1 AND consumed_at IS NULL', [emailKey, now]);
      await client.query('INSERT INTO auth.challenges(id,email,email_key,proof_hash,code_hash,link_hash,created_at,expires_at,mail_envelope) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)',
        [id, email, emailKey, proof, this.secrets.hash('code/' + id, mail.code), this.secrets.hash('link/' + id, mail.token), now, now + AUTH.challengeMs, this.secrets.seal('mail/' + id, mail)]);
    });
    // Same public response for new and existing accounts; account creation waits for verification.
    return { challengeId: id, expiresInSeconds: AUTH.challengeMs / 1000 };
  }
  async deliverPending(limit = 10) {
    const now = this.clock(), lease = randomUUID();
    const rows = await this.pool.query<{ id: string; mail_envelope: string }>(`WITH ready AS (
      SELECT id FROM auth.challenges WHERE consumed_at IS NULL AND expires_at>$1 AND mail_envelope IS NOT NULL AND mail_lease_until<=$1 AND mail_attempts<8
      ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT $2)
      UPDATE auth.challenges c SET mail_lease=$3,mail_lease_until=$1+30000,mail_attempts=c.mail_attempts+1 FROM ready r WHERE c.id=r.id RETURNING c.id,c.mail_envelope`, [now, Math.max(1, Math.min(10, limit)), lease]);
    for (const row of rows.rows) {
      try {
        const mail = this.secrets.open('mail/' + row.id, row.mail_envelope) as SignInMail;
        await this.mailer.send(mail);
        await this.pool.query('UPDATE auth.challenges SET mail_envelope=NULL WHERE id=$1 AND mail_lease=$2', [row.id, lease]);
      } catch { /* Keep encrypted, bounded retry state. No email, token or provider exception is logged. */ }
    }
  }
  private async tokens(client: PoolClient, sessionId: string, userId: string, expires: number): Promise<AuthSession> {
    const accessToken = credential(), refreshToken = credential(), accessExpires = Math.min(this.clock() + AUTH.accessMs, expires);
    await client.query('INSERT INTO auth.session_tokens(refresh_hash,access_hash,session_id,access_expires_at) VALUES($1,$2,$3,$4)',
      [this.secrets.hash('refresh', refreshToken), this.secrets.hash('access', accessToken), sessionId, accessExpires]);
    return { accessToken, refreshToken, accessExpiresAt: new Date(accessExpires).toISOString(), sessionExpiresAt: new Date(expires).toISOString(), userId };
  }
  async verify(input: { challengeId: unknown; verifier: unknown; code?: unknown; token?: unknown; idempotencyId: unknown }): Promise<AuthSession> {
    if (!uuid(input.challengeId) || !uuid(input.idempotencyId) || !isCredential(input.verifier)) throw unavailable();
    const id = input.challengeId, proof = proofHash(input.verifier), now = this.clock();
    const result = await this.transaction(async client => {
      // Email -> account -> row locks: deletion uses the same order. A pre-read
      // grants no authority; the locked challenge is always checked again below.
      const hint = (await client.query<Challenge>('SELECT * FROM auth.challenges WHERE id=$1', [id])).rows[0];
      if (!hint) return null;
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,890008))', [hint.email_key]);
      const existing = (await client.query<{id:string}>('SELECT id FROM auth.users WHERE email=$1', [hint.email])).rows[0];
      const candidate = existing?.id ?? randomUUID(); await lockAccount(client,candidate);
      if ((await client.query('SELECT 1 FROM auth.account_lifecycle WHERE user_id=$1 AND deleting', [candidate])).rowCount) return null;
      const row = (await client.query<Challenge>('SELECT * FROM auth.challenges WHERE id=$1 FOR UPDATE', [id])).rows[0];
      if (!row || Number(row.expires_at) <= now || !equalHash(row.proof_hash, proof)) return null;
      const codeValid = typeof input.code === 'string' && /^\d{6}$/.test(input.code) && equalHash(row.code_hash, this.secrets.hash('code/' + id, input.code));
      const linkValid = isCredential(input.token) && equalHash(row.link_hash, this.secrets.hash('link/' + id, input.token));
      const valid = (input.code === undefined) !== (input.token === undefined) && (codeValid || linkValid);
      if (row.consumed_at != null) {
        if (valid && row.exchange_id === input.idempotencyId && row.exchange_envelope && Number(row.exchange_expires_at) > now) return this.secrets.open('exchange/' + id, row.exchange_envelope) as AuthSession;
        return null;
      }
      if (row.attempts >= AUTH.attempts) return null;
      await client.query('UPDATE auth.challenges SET attempts=attempts+1,consumed_at=CASE WHEN $2 OR attempts+1>=5 THEN $3 ELSE consumed_at END,mail_envelope=CASE WHEN $2 OR attempts+1>=5 THEN NULL ELSE mail_envelope END WHERE id=$1', [id, valid, now]);
      if (!valid) return null; // Commit failed-attempt counters, never roll them back with the 401.
      const user = (await client.query<{ id: string }>('INSERT INTO auth.users VALUES($1,$2,$3) ON CONFLICT(email) DO UPDATE SET email=EXCLUDED.email RETURNING id', [candidate, row.email, now])).rows[0];
      const sessionId = randomUUID(), expires = now + AUTH.sessionMs;
      await client.query('INSERT INTO auth.sessions(id,user_id,created_at,expires_at) VALUES($1,$2,$3,$4)', [sessionId, user.id, now, expires]);
      const session = await this.tokens(client, sessionId, user.id, expires);
      await client.query('UPDATE auth.challenges SET exchange_id=$2,exchange_envelope=$3,exchange_expires_at=$4 WHERE id=$1', [id, input.idempotencyId, this.secrets.seal('exchange/' + id, session), Math.min(now + 30_000, Number(row.expires_at))]);
      return session;
    });
    if (!result) throw unavailable(); return result;
  }
  async refresh(token: unknown, idempotencyId: unknown): Promise<AuthSession> {
    if (!isCredential(token) || !uuid(idempotencyId)) throw unavailable(); const hash = this.secrets.hash('refresh', token), now = this.clock();
    const result = await this.transaction(async client => {
      const hint = (await client.query<{user_id:string}>('SELECT s.user_id FROM auth.session_tokens t JOIN auth.sessions s ON s.id=t.session_id WHERE t.refresh_hash=$1',[hash])).rows[0];
      if (!hint) return null;
      await lockAccount(client,hint.user_id);
      if ((await client.query('SELECT 1 FROM auth.account_lifecycle WHERE user_id=$1 AND deleting',[hint.user_id])).rowCount) return null;
      const row = (await client.query<{ session_id: string; user_id: string; expires_at: string; revoked: boolean; used: boolean; rotation_id: string | null; rotation_envelope: string | null; rotation_expires_at: string | null }>('SELECT t.session_id,t.used,t.rotation_id,t.rotation_envelope,t.rotation_expires_at,s.user_id,s.expires_at,s.revoked FROM auth.session_tokens t JOIN auth.sessions s ON s.id=t.session_id WHERE t.refresh_hash=$1 FOR UPDATE OF s,t', [hash])).rows[0];
      if (!row) return null;
      if (row.used && !row.revoked && Number(row.expires_at) > now && row.rotation_id === idempotencyId && row.rotation_envelope && Number(row.rotation_expires_at) > now) return this.secrets.open('rotation/' + hash, row.rotation_envelope) as AuthSession;
      if (row.used || row.revoked || Number(row.expires_at) <= now) { await client.query('UPDATE auth.sessions SET revoked=true WHERE id=$1', [row.session_id]); return null; }
      const session = await this.tokens(client, row.session_id, row.user_id, Number(row.expires_at));
      await client.query('UPDATE auth.session_tokens SET used=true,rotation_id=$2,rotation_envelope=$3,rotation_expires_at=$4 WHERE refresh_hash=$1', [hash, idempotencyId, this.secrets.seal('rotation/' + hash, session), Math.min(now + 30_000, Number(row.expires_at))]);
      return session;
    });
    if (!result) throw unavailable(); return result;
  }
  async user(accessToken: unknown) {
    if (!isCredential(accessToken)) throw unavailable(); const now = this.clock();
    const row = (await this.pool.query<{ id: string; email: string }>('SELECT u.id,u.email FROM auth.session_tokens t JOIN auth.sessions s ON s.id=t.session_id JOIN auth.users u ON u.id=s.user_id LEFT JOIN auth.account_lifecycle a ON a.user_id=u.id WHERE NOT COALESCE(a.deleting,false) AND t.access_hash=$1 AND NOT t.used AND NOT s.revoked AND t.access_expires_at>$2 AND s.expires_at>$2', [this.secrets.hash('access', accessToken), now])).rows[0];
    if (!row) throw unavailable(); return row;
  }
  async logout(refreshToken: unknown) {
    if (!isCredential(refreshToken)) throw unavailable();
    await this.pool.query('UPDATE auth.sessions SET revoked=true WHERE id=(SELECT session_id FROM auth.session_tokens WHERE refresh_hash=$1)', [this.secrets.hash('refresh', refreshToken)]);
  }
  async prune() {
    const now = this.clock();
    await this.pool.query('UPDATE auth.challenges SET exchange_envelope=NULL WHERE exchange_expires_at<=$1 AND exchange_envelope IS NOT NULL', [now]);
    await this.pool.query('UPDATE auth.session_tokens SET rotation_envelope=NULL WHERE rotation_expires_at<=$1 AND rotation_envelope IS NOT NULL', [now]);
    await this.pool.query('DELETE FROM auth.challenges WHERE expires_at<=$1', [now]);
    await this.pool.query('DELETE FROM auth.sessions WHERE expires_at<=$1', [now]);
    await this.pool.query('DELETE FROM auth.rate_buckets WHERE expires_at<=$1', [now]);
  }
}

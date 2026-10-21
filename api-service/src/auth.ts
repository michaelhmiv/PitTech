import { randomUUID } from 'node:crypto';
import type { Pool, PoolClient } from 'pg';
import { ApiError } from './core.ts';
import { lockAccount } from './lifecycle-contract.ts';
import { AUTH, AuthSecrets, credential, emailAddress, equalHash, isCredential, proofHash } from './auth-credentials.ts';
import type { AuthMailer, SignInMail } from './auth-email.ts';
import { appleEventOperationId, verifyAppleProviderNotification, verifyProviderToken, type ProviderName, type ProviderServices, type VerifiedProviderClaims } from './auth-providers.ts';

const uuid = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(value);
const unavailable = () => new ApiError(401, 'sign_in_unavailable');
const linkRequired = () => new ApiError(409, 'identity_link_required');
type Challenge = { id: string; email: string; email_key: string; proof_hash: string; code_hash: string; link_hash: string; created_at: string; expires_at: string; attempts: number; consumed_at: string | null; exchange_id: string | null; exchange_envelope: string | null; exchange_expires_at: string | null };
type ProviderChallenge = { id: string; provider: string; proof_hash: string; user_id: string | null; created_at: string; expires_at: string; attempts: number; consumed_at: string | null; exchange_id: string | null; exchange_envelope: string | null; exchange_expires_at: string | null };
/** The provider-verified account email, if the token carries one usable for
 * account decisions. Apple asserts verified addresses by construction; Google
 * requires an explicit verified flag. Malformed claims match nothing. */
function providerAccountEmail(claims: VerifiedProviderClaims | undefined, provider: ProviderName): string | undefined {
  if (!claims?.email || (provider === 'google' && !claims.emailVerified)) return undefined;
  try { return emailAddress(claims.email); } catch { return undefined; }
}
export type AuthSession = { accessToken: string; refreshToken: string; accessExpiresAt: string; sessionExpiresAt: string; userId: string };
export type IdentityList = { identities: { provider: string }[] };
export class Authentication {
  private pool: Pool; private secrets: AuthSecrets; private mailer: AuthMailer; private dailyEmails: number; clock: () => number;
  readonly providers?: ProviderServices;
  constructor(pool: Pool, secrets: AuthSecrets, mailer: AuthMailer, dailyEmails: number, clock = Date.now, providers?: ProviderServices) {
    if (!Number.isSafeInteger(dailyEmails) || dailyEmails < 1) throw new Error('Auth email budget required');
    this.pool = pool; this.secrets = secrets; this.mailer = mailer; this.dailyEmails = dailyEmails; this.clock = clock; this.providers = providers;
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
  /** Provider proof-bound challenge. The client offers the stored proof hash
   * as the provider nonce; with an access bearer the challenge binds to that
   * account for the attach flow. Unconfigured providers refuse silently. */
  async startProvider(input: { provider: unknown; idempotencyId: unknown; proofChallenge: unknown }, accessToken?: unknown) {
    const services = this.providers, provider = input.provider as ProviderName;
    if (!services || (provider !== 'apple' && provider !== 'google') || (provider === 'apple' ? !services.apple : !services.google)) throw unavailable();
    const id = input.idempotencyId, proof = input.proofChallenge;
    if (!uuid(id) || typeof proof !== 'string' || !/^[a-f0-9]{64}$/.test(proof)) throw new ApiError(400, 'sign_in_request_invalid');
    const bound = accessToken === undefined ? undefined : await this.user(accessToken);
    const now = this.clock();
    await this.transaction(async client => {
      const prior = (await client.query<ProviderChallenge>('SELECT * FROM auth.provider_challenges WHERE id=$1', [id])).rows[0];
      if (prior) {
        if (prior.provider !== provider || !equalHash(prior.proof_hash, proof) || (prior.user_id ?? null) !== (bound?.id ?? null) || prior.consumed_at != null || Number(prior.expires_at) <= now) throw new ApiError(409, 'sign_in_request_unavailable');
        return;
      }
      await client.query('INSERT INTO auth.provider_challenges(id,provider,proof_hash,user_id,created_at,expires_at) VALUES($1,$2,$3,$4,$5,$6)',
        [id, provider, proof, bound?.id ?? null, now, now + AUTH.challengeMs]);
    });
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
  /** Signed-out provider exchange. A linked identity signs in (the email claim
   * is irrelevant there); a verified email matching an unlinked account never
   * merges and reports 409; creation needs a provider-verified email. Attempt
   * counting, consumption, the recovery envelope and exact-retry semantics copy
   * email verify, including committing counters with every refusal. */
  async verifyProvider(input: { challengeId: unknown; verifier: unknown; idToken: unknown; idempotencyId: unknown }): Promise<AuthSession> {
    const services = this.providers; if (!services) throw unavailable();
    if (!uuid(input.challengeId) || !uuid(input.idempotencyId) || !isCredential(input.verifier) ||
        typeof input.idToken !== 'string' || !input.idToken || input.idToken.length > 8192) throw unavailable();
    const id = input.challengeId, proof = proofHash(input.verifier), now = this.clock();
    type Outcome = { status: 'session'; session: AuthSession } | { status: 'link-required' } | { status: 'invalid' };
    const result = await this.transaction(async client => {
      const invalid = { status: 'invalid' } as Outcome;
      const hint = (await client.query<ProviderChallenge>('SELECT * FROM auth.provider_challenges WHERE id=$1', [id])).rows[0];
      if (!hint || (hint.provider !== 'apple' && hint.provider !== 'google')) return invalid;
      const provider = hint.provider as ProviderName;
      if ((provider === 'apple' ? services.apple : services.google) === undefined) return invalid;
      let claims: VerifiedProviderClaims | undefined;
      try { claims = verifyProviderToken(input.idToken, provider, services, proof, now); }
      catch { claims = undefined; }
      const subjectKey = claims ? this.secrets.hash('provider/' + provider, claims.sub) : undefined;
      const usableEmail = providerAccountEmail(claims, provider);
      const linked = subjectKey ? (await client.query<{ user_id: string }>('SELECT user_id FROM auth.provider_identities WHERE provider=$1 AND subject_key=$2', [provider, subjectKey])).rows[0] : undefined;
      const existing = usableEmail ? (await client.query<{ id: string }>('SELECT id FROM auth.users WHERE email=$1', [usableEmail])).rows[0] : undefined;
      // Email -> account -> row locks: deletion uses the same order. Post-lock
      // reads below are authoritative; anything needing a different account
      // lock is refused conservatively instead of locking out of order.
      if (usableEmail) await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,890008))', [this.secrets.hash('email', usableEmail)]);
      const candidate = linked?.user_id ?? existing?.id ?? randomUUID(); await lockAccount(client, candidate);
      if ((await client.query('SELECT 1 FROM auth.account_lifecycle WHERE user_id=$1 AND deleting', [candidate])).rowCount) return invalid;
      const row = (await client.query<ProviderChallenge>('SELECT * FROM auth.provider_challenges WHERE id=$1 FOR UPDATE', [id])).rows[0];
      if (!row || Number(row.expires_at) <= now || !equalHash(row.proof_hash, proof)) return invalid;
      if (row.consumed_at != null) {
        if (claims && row.exchange_id === input.idempotencyId && row.exchange_envelope && Number(row.exchange_expires_at) > now) {
          // Exact retry only: the envelope is bound to the subject that
          // consumed the challenge, so a different provider token on the
          // same proof/idempotency pair can never replay the session.
          const envelope = this.secrets.open('provider-exchange/' + id, row.exchange_envelope) as { session: AuthSession; subjectKey: string };
          if (envelope.subjectKey === subjectKey) return { status: 'session', session: envelope.session } as Outcome;
        }
        return invalid;
      }
      if (row.attempts >= AUTH.attempts) return invalid;
      const live = subjectKey ? (await client.query<{ user_id: string }>('SELECT user_id FROM auth.provider_identities WHERE provider=$1 AND subject_key=$2', [provider, subjectKey])).rows[0] : undefined;
      const account = usableEmail ? (await client.query<{ id: string }>('SELECT id FROM auth.users WHERE email=$1', [usableEmail])).rows[0] : undefined;
      const linkedMatch = !!claims && !!live && live.user_id === candidate && (row.user_id == null || row.user_id === live.user_id);
      const creating = !!claims && !live && !!usableEmail && !account && row.user_id == null;
      const valid = linkedMatch || creating;
      await client.query('UPDATE auth.provider_challenges SET attempts=attempts+1,consumed_at=CASE WHEN $2 OR attempts+1>=5 THEN $3 ELSE consumed_at END WHERE id=$1', [id, valid, now]);
      if (!valid) return claims && (live || usableEmail) ? { status: 'link-required' } as Outcome : invalid;
      if (linkedMatch) {
        const sessionId = randomUUID(), expires = now + AUTH.sessionMs;
        await client.query('INSERT INTO auth.sessions(id,user_id,created_at,expires_at) VALUES($1,$2,$3,$4)', [sessionId, live!.user_id, now, expires]);
        const session = await this.tokens(client, sessionId, live!.user_id, expires);
        await client.query('UPDATE auth.provider_challenges SET exchange_id=$2,exchange_envelope=$3,exchange_expires_at=$4 WHERE id=$1', [id, input.idempotencyId, this.secrets.seal('provider-exchange/' + id, { session, subjectKey }), Math.min(now + 30_000, Number(row.expires_at))]);
        return { status: 'session', session } as Outcome;
      }
      const user = (await client.query<{ id: string }>('INSERT INTO auth.users VALUES($1,$2,$3) ON CONFLICT(email) DO UPDATE SET email=EXCLUDED.email RETURNING id', [candidate, usableEmail!, now])).rows[0];
      if (user.id !== candidate) return { status: 'link-required' } as Outcome;
      const identity = await client.query('INSERT INTO auth.provider_identities(user_id,provider,subject_key,email,created_at) VALUES($1,$2,$3,$4,$5) ON CONFLICT DO NOTHING RETURNING user_id', [user.id, provider, subjectKey!, usableEmail!, now]);
      if (!identity.rowCount) return { status: 'link-required' } as Outcome;
      const sessionId = randomUUID(), expires = now + AUTH.sessionMs;
      await client.query('INSERT INTO auth.sessions(id,user_id,created_at,expires_at) VALUES($1,$2,$3,$4)', [sessionId, user.id, now, expires]);
      const session = await this.tokens(client, sessionId, user.id, expires);
      await client.query('UPDATE auth.provider_challenges SET exchange_id=$2,exchange_envelope=$3,exchange_expires_at=$4 WHERE id=$1', [id, input.idempotencyId, this.secrets.seal('provider-exchange/' + id, { session, subjectKey }), Math.min(now + 30_000, Number(row.expires_at))]);
      return { status: 'session', session } as Outcome;
    });
    if (result.status === 'invalid') throw unavailable();
    if (result.status === 'link-required') throw linkRequired();
    return result.session;
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
    if (!row) throw unavailable();
    const identities = (await this.pool.query<{ provider: string }>('SELECT provider FROM auth.provider_identities WHERE user_id=$1 ORDER BY provider', [row.id])).rows;
    return { ...row, identities: identities.map(entry => ({ provider: entry.provider })) };
  }
  async logout(refreshToken: unknown) {
    if (!isCredential(refreshToken)) throw unavailable();
    await this.pool.query('UPDATE auth.sessions SET revoked=true WHERE id=(SELECT session_id FROM auth.session_tokens WHERE refresh_hash=$1)', [this.secrets.hash('refresh', refreshToken)]);
  }
  /** Attach-while-signed-in: the only linking path. The challenge must have
   * been created bound to the session account; an identity linked elsewhere
   * reports 409 without hinting at the other account. No session is issued. */
  async attachIdentity(accessToken: unknown, input: { challengeId: unknown; verifier: unknown; idToken: unknown; idempotencyId: unknown }): Promise<IdentityList> {
    const services = this.providers; if (!services) throw unavailable();
    const user = await this.user(accessToken);
    if (!uuid(input.challengeId) || !uuid(input.idempotencyId) || !isCredential(input.verifier) ||
        typeof input.idToken !== 'string' || !input.idToken || input.idToken.length > 8192) throw unavailable();
    const id = input.challengeId, proof = proofHash(input.verifier), now = this.clock();
    type Outcome = { status: 'linked'; identities: { provider: string }[] } | { status: 'link-required' } | { status: 'invalid' };
    const result = await this.transaction(async client => {
      const invalid = { status: 'invalid' } as Outcome;
      const hint = (await client.query<ProviderChallenge>('SELECT * FROM auth.provider_challenges WHERE id=$1', [id])).rows[0];
      if (!hint || (hint.provider !== 'apple' && hint.provider !== 'google')) return invalid;
      const provider = hint.provider as ProviderName;
      if ((provider === 'apple' ? services.apple : services.google) === undefined) return invalid;
      let claims: VerifiedProviderClaims | undefined;
      try { claims = verifyProviderToken(input.idToken, provider, services, proof, now); }
      catch { claims = undefined; }
      await lockAccount(client, user.id);
      if (!(await client.query('SELECT 1 FROM auth.users WHERE id=$1', [user.id])).rowCount ||
          (await client.query('SELECT 1 FROM auth.account_lifecycle WHERE user_id=$1 AND deleting', [user.id])).rowCount) return invalid;
      const row = (await client.query<ProviderChallenge>('SELECT * FROM auth.provider_challenges WHERE id=$1 FOR UPDATE', [id])).rows[0];
      if (!row || Number(row.expires_at) <= now || !equalHash(row.proof_hash, proof) || row.user_id !== user.id) return invalid;
      if (!claims) {
        if (row.consumed_at != null || row.attempts >= AUTH.attempts) return invalid;
        await client.query('UPDATE auth.provider_challenges SET attempts=attempts+1,consumed_at=CASE WHEN attempts+1>=5 THEN $2 ELSE consumed_at END WHERE id=$1', [id, now]);
        return invalid;
      }
      const subjectKey = this.secrets.hash('provider/' + provider, claims.sub);
      if (row.consumed_at != null) {
        if (row.exchange_id === input.idempotencyId && row.exchange_envelope && Number(row.exchange_expires_at) > now) {
          // Exact retry only, bound to the consuming subject (as in verify).
          const envelope = this.secrets.open('provider-attach/' + id, row.exchange_envelope) as IdentityList & { subjectKey: string };
          if (envelope.subjectKey === subjectKey) return { status: 'linked', identities: envelope.identities } as Outcome;
        }
        return invalid;
      }
      if (row.attempts >= AUTH.attempts) return invalid;
      const live = (await client.query<{ user_id: string }>('SELECT user_id FROM auth.provider_identities WHERE provider=$1 AND subject_key=$2', [provider, subjectKey])).rows[0];
      const mine = (await client.query<{ subject_key: string }>('SELECT subject_key FROM auth.provider_identities WHERE user_id=$1 AND provider=$2', [user.id, provider])).rows[0];
      const valid = (!live || live.user_id === user.id) && (!mine || mine.subject_key === subjectKey);
      await client.query('UPDATE auth.provider_challenges SET attempts=attempts+1,consumed_at=CASE WHEN $2 OR attempts+1>=5 THEN $3 ELSE consumed_at END WHERE id=$1', [id, valid, now]);
      if (!valid) return { status: 'link-required' } as Outcome;
      let storedEmail: string | null = null;
      if (claims.email) { try { storedEmail = emailAddress(claims.email); } catch { storedEmail = claims.email; } }
      await client.query('INSERT INTO auth.provider_identities(user_id,provider,subject_key,email,created_at) VALUES($1,$2,$3,$4,$5) ON CONFLICT DO NOTHING', [user.id, provider, subjectKey, storedEmail, now]);
      const check = (await client.query<{ user_id: string; subject_key: string }>('SELECT user_id,subject_key FROM auth.provider_identities WHERE user_id=$1 AND provider=$2', [user.id, provider])).rows[0];
      if (!check || check.subject_key !== subjectKey) return { status: 'link-required' } as Outcome;
      const identities = (await client.query<{ provider: string }>('SELECT provider FROM auth.provider_identities WHERE user_id=$1 ORDER BY provider', [user.id])).rows.map(entry => ({ provider: entry.provider }));
      await client.query('UPDATE auth.provider_challenges SET exchange_id=$2,exchange_envelope=$3,exchange_expires_at=$4 WHERE id=$1', [id, input.idempotencyId, this.secrets.seal('provider-attach/' + id, { identities, subjectKey }), Math.min(now + 30_000, Number(row.expires_at))]);
      return { status: 'linked', identities } as Outcome;
    });
    if (result.status === 'invalid') throw unavailable();
    if (result.status === 'link-required') throw linkRequired();
    return { identities: result.identities };
  }
  /** Sign in with Apple server-to-server notifications. Bad signatures are a
   * 401 with nothing recorded; verified events dedupe durably on the Apple
   * event identifier and always answer 200, including unknown subjects. */
  async appleNotification(input: { signedPayload: unknown }): Promise<{ outcome: 'applied' | 'deduped' | 'recorded'; note: string }> {
    const services = this.providers;
    if (!services?.apple || !services.deletion) throw new ApiError(503, 'temporarily_unavailable');
    const now = this.clock(), event = verifyAppleProviderNotification(input, services, now);
    const record = async () => !!((await this.pool.query('INSERT INTO auth.apple_notification_receipts(event_id,received_at) VALUES($1,$2) ON CONFLICT DO NOTHING RETURNING event_id', [event.eventId, now])).rowCount);
    if ((await this.pool.query('SELECT 1 FROM auth.apple_notification_receipts WHERE event_id=$1', [event.eventId])).rowCount) return { outcome: 'deduped', note: 'replay' };
    if (event.type === 'unknown') return (await record()) ? { outcome: 'recorded', note: 'unsupported-event' } : { outcome: 'deduped', note: 'replay' };
    const linked = (await this.pool.query<{ user_id: string }>('SELECT user_id FROM auth.provider_identities WHERE provider=$1 AND subject_key=$2', ['apple', this.secrets.hash('provider/apple', event.sub)])).rows[0];
    if (!linked) return (await record()) ? { outcome: 'recorded', note: 'unknown-subject' } : { outcome: 'deduped', note: 'replay' };
    if (event.type === 'email-enabled' || event.type === 'email-disabled') {
      if (event.email) await this.pool.query('UPDATE auth.provider_identities SET email=$3 WHERE provider=$1 AND subject_key=$2', ['apple', this.secrets.hash('provider/apple', event.sub), event.email]);
      return (await record()) ? { outcome: 'applied', note: event.type } : { outcome: 'deduped', note: 'replay' };
    }
    await services.deletion.requestForAppleEvent({ operationId: appleEventOperationId(event.eventId), userId: linked.user_id });
    return (await record()) ? { outcome: 'applied', note: event.type } : { outcome: 'deduped', note: 'replay' };
  }
  async prune() {
    const now = this.clock();
    await this.pool.query('UPDATE auth.challenges SET exchange_envelope=NULL WHERE exchange_expires_at<=$1 AND exchange_envelope IS NOT NULL', [now]);
    await this.pool.query('UPDATE auth.provider_challenges SET exchange_envelope=NULL WHERE exchange_expires_at<=$1 AND exchange_envelope IS NOT NULL', [now]);
    await this.pool.query('UPDATE auth.session_tokens SET rotation_envelope=NULL WHERE rotation_expires_at<=$1 AND rotation_envelope IS NOT NULL', [now]);
    await this.pool.query('DELETE FROM auth.challenges WHERE expires_at<=$1', [now]);
    await this.pool.query('DELETE FROM auth.provider_challenges WHERE expires_at<=$1', [now]);
    await this.pool.query('DELETE FROM auth.sessions WHERE expires_at<=$1', [now]);
    await this.pool.query('DELETE FROM auth.rate_buckets WHERE expires_at<=$1', [now]);
  }
}

import { createRuntime } from './config.ts';
import { makeServer, pruneUploads, tempRoot } from './http.ts';
import { FileStorage } from './storage.ts';
import { productionCommunityLoad, startCommunityScheduler } from './community-scheduler.ts';

try {
  const runtime = createRuntime();
  await runtime.pool.query('SELECT 1 FROM relay.packs LIMIT 1');
  if (runtime.auth) await runtime.pool.query('SELECT 1 FROM auth.challenges LIMIT 1');
  if (runtime.publications) await runtime.pool.query('SELECT 1 FROM publications.items LIMIT 1');
  if(runtime.lifecyclePool){await runtime.lifecyclePool.query('SELECT 1 FROM moderation.deletions LIMIT 1');await runtime.lifecyclePool.query('SELECT 1 FROM private_sync.accounts LIMIT 1');}
  if(runtime.syncPool)await runtime.syncPool.query('SELECT 1 FROM private_sync.accounts LIMIT 1');
  if(runtime.subscriptions){await runtime.pool.query('SELECT 1 FROM billing.entitlements LIMIT 1');try{await runtime.refreshOidcKeys?.();}catch{console.error('billing_oidc_deferred');}}
  if(runtime.communityAggregates)await runtime.pool.query('SELECT 1 FROM community_aggregation.job_runs LIMIT 1');
  await pruneUploads(tempRoot);
  const server = makeServer(runtime.relay, { ...runtime.options, log: value => console.info(JSON.stringify(value)) });
  server.listen(runtime.port, '0.0.0.0');
  let sweeping = false;
  const timer = setInterval(async () => {
    if (sweeping) return; sweeping = true;
    try { await runtime.relay.sweep(); if(runtime.publications)await runtime.publications.sweep(); if(runtime.deletion)await runtime.deletion.sweepDeletion(); if(runtime.subscriptions)await runtime.subscriptions.reconcileStale(86400000,20); await pruneUploads(tempRoot); if (runtime.relay.storage instanceof FileStorage) await runtime.relay.storage.prune(Date.now()); }
    catch { console.error('relay_cleanup_deferred'); } finally { sweeping = false; }
  }, 60000); timer.unref();
  const oidcTimer = runtime.subscriptions ? setInterval(async () => {
    try { await runtime.refreshOidcKeys?.(); } catch { console.error('billing_oidc_deferred'); }
  }, 300000) : undefined; oidcTimer?.unref();
  let mailing = false;
  const authTimer = runtime.auth ? setInterval(async () => {
    if (mailing) return; mailing = true;
    try { await runtime.auth!.deliverPending(); await runtime.auth!.prune(); if(runtime.deletion)await runtime.deletion.deliverNotices(); }
    catch { console.error('auth_maintenance_deferred'); } finally { mailing = false; }
  }, 15000) : undefined; authTimer?.unref();
  // Community aggregation runs only when COMMUNITY_AGGREGATION_ENABLED=true;
  // when off no timer exists and the job never runs (fail closed).
  const communityScheduler = runtime.communityAggregates
    ? startCommunityScheduler({ store: runtime.communityAggregates.store, load: productionCommunityLoad(runtime.pool) })
    : undefined;
  for (const signal of ['SIGTERM', 'SIGINT']) process.once(signal, () => {
    clearInterval(timer); if (authTimer) clearInterval(authTimer); if (oidcTimer) clearInterval(oidcTimer); communityScheduler?.stop(); server.close(() => { void runtime.close(); });
    setTimeout(() => process.exit(0), 40000).unref();
  });
} catch { console.error('service_start_failed'); process.exitCode = 1; }

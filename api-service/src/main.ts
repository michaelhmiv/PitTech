import { createRuntime } from './config.ts';
import { makeServer, pruneUploads, tempRoot } from './http.ts';
import { FileStorage } from './storage.ts';

try {
  const runtime = createRuntime();
  await runtime.pool.query('SELECT 1 FROM relay.packs LIMIT 1');
  await pruneUploads(tempRoot);
  const server = makeServer(runtime.relay, { ...runtime.options, log: value => console.info(JSON.stringify(value)) });
  server.listen(runtime.port, '0.0.0.0');
  let sweeping = false;
  const timer = setInterval(async () => {
    if (sweeping) return; sweeping = true;
    try { await runtime.relay.sweep(); await pruneUploads(tempRoot); if (runtime.relay.storage instanceof FileStorage) await runtime.relay.storage.prune(Date.now()); }
    catch { console.error('relay_cleanup_deferred'); } finally { sweeping = false; }
  }, 60000); timer.unref();
  for (const signal of ['SIGTERM', 'SIGINT']) process.once(signal, () => {
    clearInterval(timer); server.close(() => { void runtime.pool.end(); });
    setTimeout(() => process.exit(0), 40000).unref();
  });
} catch { console.error('service_start_failed'); process.exitCode = 1; }

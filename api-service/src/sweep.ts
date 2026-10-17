import { createRuntime } from './config.ts';
import { pruneUploads, tempRoot } from './http.ts';
import { FileStorage } from './storage.ts';

try {
  const runtime = createRuntime();
  try {
    let removed = 0; for (let i = 0; i < 10; i++) { const count = await runtime.relay.sweep(100); removed += count; if (count < 100) break; }
    if(runtime.publications)await runtime.publications.sweep();
    if(runtime.deletion){await runtime.deletion.sweepDeletion();await runtime.deletion.deliverNotices();}
    await pruneUploads(tempRoot); if (runtime.relay.storage instanceof FileStorage) await runtime.relay.storage.prune(Date.now());
    console.info(JSON.stringify({ removed }));
  } finally { await runtime.close(); }
} catch { console.error('relay_cleanup_deferred'); process.exitCode = 1; }

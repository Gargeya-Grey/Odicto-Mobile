import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Pool } from 'pg';
import { createApp } from './app.js';
import { loadConfig } from './config.js';
import { MemoryInstallationStore } from './auth.js';
import { PostgresInstallationStore } from './db/postgres-store.js';

function loadEnvFile() {
  const envPath = resolve(dirname(fileURLToPath(import.meta.url)), '../.env');
  try {
    for (const line of readFileSync(envPath, 'utf8').split(/\r?\n/)) {
      const match = line.match(/^([A-Z0-9_]+)=(.*)$/);
      // A local .env file is authoritative; in production there is no
      // .env file, so injected environment variables govern there.
      if (match) process.env[match[1]] = match[2].trim();
    }
  } catch {
    // .env is optional
  }
}

loadEnvFile();
const config = loadConfig();
const pool = config.DATABASE_URL
  ? new Pool({ connectionString: config.DATABASE_URL, max: 10 })
  : null;
const store = pool
  ? new PostgresInstallationStore(pool)
  : new MemoryInstallationStore();
const app = createApp(config, store);
app.addHook('onClose', async () => {
  await pool?.end();
});
await app.listen({ port: config.PORT, host: '0.0.0.0' });

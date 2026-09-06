import { Pool } from 'pg';
import type { Installation, InstallationStore } from '../auth.js';

export class PostgresInstallationStore implements InstallationStore {
  constructor(private pool: Pool) {}
  async create(
    installation: Installation,
    refreshHash: string,
    expiresAt: Date,
  ) {
    const client = await this.pool.connect();
    try {
      await client.query('BEGIN');
      await client.query(
        'INSERT INTO installations (id, tier, platform, app_version) VALUES ($1,$2,$3,$4)',
        [
          installation.id,
          installation.tier,
          installation.platform,
          installation.appVersion,
        ],
      );
      await client.query(
        'INSERT INTO installation_credentials (token_hash, installation_id, expires_at) VALUES ($1,$2,$3)',
        [refreshHash, installation.id, expiresAt],
      );
      await client.query('COMMIT');
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      client.release();
    }
  }
  async rotate(refreshHash: string, replacementHash: string, expiresAt: Date) {
    const client = await this.pool.connect();
    try {
      await client.query('BEGIN');
      const result = await client.query<{
        id: string;
        tier: Installation['tier'];
        platform: string;
        app_version: string;
      }>(
        'SELECT i.id,i.tier,i.platform,i.app_version FROM installation_credentials c JOIN installations i ON i.id=c.installation_id WHERE c.token_hash=$1 AND c.revoked_at IS NULL AND c.expires_at > now() FOR UPDATE',
        [refreshHash],
      );
      const row = result.rows[0];
      if (!row) {
        await client.query('ROLLBACK');
        return null;
      }
      await client.query(
        'UPDATE installation_credentials SET revoked_at=now() WHERE token_hash=$1',
        [refreshHash],
      );
      await client.query(
        'INSERT INTO installation_credentials (token_hash, installation_id, expires_at) VALUES ($1,$2,$3)',
        [replacementHash, row.id, expiresAt],
      );
      await client.query(
        'UPDATE installations SET last_seen_at=now() WHERE id=$1',
        [row.id],
      );
      await client.query('COMMIT');
      return {
        id: row.id,
        tier: row.tier,
        platform: row.platform,
        appVersion: row.app_version,
      };
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      client.release();
    }
  }
  async getUsage(installationId: string) {
    const result = await this.pool.query<{ used: string }>(
      "SELECT COALESCE(sum(audio_seconds),0)::text used FROM usage_ledger WHERE installation_id=$1 AND state IN ('reserved','completed') AND created_at >= date_trunc('month', now())",
      [installationId],
    );
    return Number(result.rows[0]?.used ?? 0);
  }
  async reserve(
    installationId: string,
    operationId: string,
    seconds: number,
    limit: number,
  ) {
    const client = await this.pool.connect();
    try {
      await client.query('BEGIN');
      await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [
        installationId,
      ]);
      const existing = await client.query(
        'SELECT 1 FROM usage_ledger WHERE installation_id=$1 AND operation_id=$2',
        [installationId, operationId],
      );
      if (existing.rowCount) {
        await client.query('COMMIT');
        return 'duplicate' as const;
      }
      const usage = await client.query<{ used: string }>(
        "SELECT COALESCE(sum(audio_seconds),0)::text used FROM usage_ledger WHERE installation_id=$1 AND state IN ('reserved','completed') AND created_at >= date_trunc('month', now())",
        [installationId],
      );
      if (Number(usage.rows[0]?.used ?? 0) + seconds > limit) {
        await client.query('ROLLBACK');
        return 'exhausted' as const;
      }
      await client.query(
        "INSERT INTO usage_ledger (installation_id,operation_id,state,audio_seconds) VALUES ($1,$2,'reserved',$3)",
        [installationId, operationId, seconds],
      );
      await client.query('COMMIT');
      return 'reserved' as const;
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      client.release();
    }
  }
  async finalize(
    installationId: string,
    operationId: string,
    details: {
      seconds: number;
      provider: string;
      model: string;
      failure?: string;
    },
  ) {
    await this.pool.query(
      'UPDATE usage_ledger SET state=$3,audio_seconds=$4,provider=$5,model=$6,failure_class=$7,updated_at=now() WHERE installation_id=$1 AND operation_id=$2',
      [
        installationId,
        operationId,
        details.failure ? 'failed' : 'completed',
        details.seconds,
        details.provider,
        details.model,
        details.failure ?? null,
      ],
    );
  }
}

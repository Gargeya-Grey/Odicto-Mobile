import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { SignJWT, jwtVerify } from 'jose';

export type Installation = {
  id: string;
  tier: 'free' | 'premium' | 'byok';
  platform: string;
  appVersion: string;
};
export type StoredCredential = Installation & { expiresAt: Date };
export interface InstallationStore {
  create(
    installation: Installation,
    refreshHash: string,
    expiresAt: Date,
  ): Promise<void>;
  rotate(
    refreshHash: string,
    replacementHash: string,
    expiresAt: Date,
  ): Promise<Installation | null>;
  getUsage(installationId: string): Promise<number>;
  reserve(
    installationId: string,
    operationId: string,
    seconds: number,
    limit: number,
  ): Promise<'reserved' | 'duplicate' | 'exhausted'>;
  finalize(
    installationId: string,
    operationId: string,
    details: {
      seconds: number;
      provider: string;
      model: string;
      failure?: string;
    },
  ): Promise<void>;
}

export class MemoryInstallationStore implements InstallationStore {
  private credentials = new Map<string, StoredCredential>();
  private usage = new Map<string, Map<string, number>>();
  async create(
    installation: Installation,
    refreshHash: string,
    expiresAt: Date,
  ) {
    this.credentials.set(refreshHash, { ...installation, expiresAt });
  }
  async rotate(refreshHash: string, replacementHash: string, expiresAt: Date) {
    const current = this.credentials.get(refreshHash);
    if (!current || current.expiresAt < new Date()) return null;
    this.credentials.delete(refreshHash);
    this.credentials.set(replacementHash, { ...current, expiresAt });
    return {
      id: current.id,
      tier: current.tier,
      platform: current.platform,
      appVersion: current.appVersion,
    };
  }
  async getUsage(id: string) {
    return [...(this.usage.get(id)?.values() ?? [])].reduce(
      (sum, value) => sum + value,
      0,
    );
  }
  async reserve(
    id: string,
    operationId: string,
    seconds: number,
    limit: number,
  ) {
    const ledger = this.usage.get(id) ?? new Map<string, number>();
    if (ledger.has(operationId)) return 'duplicate' as const;
    const used = [...ledger.values()].reduce((sum, value) => sum + value, 0);
    if (used + seconds > limit) return 'exhausted' as const;
    ledger.set(operationId, seconds);
    this.usage.set(id, ledger);
    return 'reserved' as const;
  }
  async finalize(
    id: string,
    operationId: string,
    details: { seconds: number },
  ) {
    const ledger = this.usage.get(id);
    if (ledger?.has(operationId)) ledger.set(operationId, details.seconds);
  }
}

const hash = (value: string) =>
  createHash('sha256').update(value).digest('hex');
export class InstallationAuth {
  private key: Uint8Array;
  constructor(
    private secret: string,
    private store: InstallationStore,
  ) {
    this.key = new TextEncoder().encode(secret);
  }
  async issue(platform: string, appVersion: string, refreshToken?: string) {
    const nextRefresh = randomBytes(48).toString('base64url');
    const refreshExpiresAt = new Date(Date.now() + 1000 * 60 * 60 * 24 * 90);
    let installation: Installation | null = refreshToken
      ? await this.store.rotate(
          hash(refreshToken),
          hash(nextRefresh),
          refreshExpiresAt,
        )
      : null;
    if (!installation) {
      installation = { id: randomUUID(), tier: 'free', platform, appVersion };
      await this.store.create(
        installation,
        hash(nextRefresh),
        refreshExpiresAt,
      );
    }
    const accessExpiresAt = new Date(Date.now() + 1000 * 60 * 15);
    const accessToken = await new SignJWT({ tier: installation.tier })
      .setProtectedHeader({ alg: 'HS256' })
      .setSubject(installation.id)
      .setIssuedAt()
      .setExpirationTime(Math.floor(accessExpiresAt.getTime() / 1000))
      .sign(this.key);
    return {
      installationId: installation.id,
      accessToken,
      accessTokenExpiresAt: accessExpiresAt.toISOString(),
      refreshToken: nextRefresh,
      refreshTokenExpiresAt: refreshExpiresAt.toISOString(),
    };
  }
  async verify(
    token: string,
  ): Promise<{ installationId: string; tier: Installation['tier'] }> {
    const { payload } = await jwtVerify(token, this.key);
    if (!payload.sub) throw new Error('Missing token subject');
    return {
      installationId: payload.sub,
      tier: payload.tier as Installation['tier'],
    };
  }
}

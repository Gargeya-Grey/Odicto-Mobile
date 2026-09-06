CREATE TABLE IF NOT EXISTS installations (
  id uuid PRIMARY KEY,
  status text NOT NULL DEFAULT 'active',
  tier text NOT NULL DEFAULT 'free',
  platform text NOT NULL,
  app_version text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS installation_credentials (
  token_hash text PRIMARY KEY,
  installation_id uuid NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz
);
CREATE TABLE IF NOT EXISTS usage_ledger (
  installation_id uuid NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
  operation_id uuid NOT NULL,
  state text NOT NULL,
  audio_seconds numeric NOT NULL DEFAULT 0,
  provider text,
  model text,
  failure_class text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (installation_id, operation_id)
);

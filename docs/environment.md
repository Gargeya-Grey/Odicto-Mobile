# Environment and operations

Copy `apps/api/.env.example` to `apps/api/.env` for local API development. Production startup rejects a missing database, Groq key, or unchanged signing secret.

## Production services

- Cloud Run: deploy `apps/api/Dockerfile`; align region with Postgres and Redis, cap concurrency/max instances, configure request timeout and billing alerts.
- Supabase Postgres: apply `apps/api/src/db/migration.sql`; connect through `DATABASE_URL` using TLS.
- Google Secret Manager: store database URL, signing secret, managed provider keys, Upstash token, and RevenueCat webhook secret under least-privilege IAM.
- Upstash: use for short-window installation/IP limits; never replace authoritative Postgres quota with Redis counters.
- RevenueCat: add after Android dictation passes the physical-device gate.

## Providers

Android voice does not use this backend configuration. It connects directly to the official providers with keys entered on the phone: Raw → Groq Whisper, AI → Groq transcription then Gemini/OpenRouter, Live → Gemini Live. There is no direct Meta API option on Android; Meta models can be selected through OpenRouter.

The remaining API workspace supports the separate web/backend flow. Its provider configuration does not configure the phone. Do not copy backend secrets into mobile assets or build resources.

## Database rules

Refresh credentials are random, rotating, and stored only as hashes. Usage reservations are keyed by `(installation_id, operation_id)` and transactionally serialized per installation. Provider retries reuse the same operation ID.

Never place secrets in Vite variables, Capacitor config, Android resources, source control, request logs, or mobile release bundles.

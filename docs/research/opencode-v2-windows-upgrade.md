# OpenCode v2 Windows Upgrade Investigation

Verified against primary sources on 2026-09-14.

## Conclusion

- The current v2 release is `@opencode/cli@2.0.3` on the NPM `latest` tag and on the update service `latest` channel.
- The current NPM `beta` tag is `0.0.0-beta-19507`, but the update service's active `beta` NPM artifact is `@opencode/cli@2.0.1`. This is a server-side channel/version mismatch, not evidence that v2.0.3 is unpublished.
- The v2 shell installer fetches its version from `https://opencode.ai/update/api/beta/cli/npm` when no version is supplied. Therefore, a fresh curl install or a curl-based upgrade currently selects 2.0.1.
- The CLI updater also uses the binary's compiled channel. A beta binary queries the beta endpoint; a latest binary queries the latest endpoint.
- The updater does not compare version ordering. It treats any valid, different version as an update, so an older channel result can be offered as an upgrade.
- Windows has an independent replacement risk. The v2 curl path runs the shell install script while the current executable is still running; Windows can reject replacement of a locked `.exe`. The v2 npm postinstall also unlinks/copies the target binary and does not implement a scheduled replacement.

## Version And Channel Evidence

Live update service responses:

- [`latest/cli/npm?current=2.0.3`](https://opencode.ai/update/api/latest/cli/npm?current=2.0.3) returns version `2.0.3`, package `@opencode/cli`, GitHub ref `refs/heads/v2`.
- [`beta/cli/npm?current=2.0.3`](https://opencode.ai/update/api/beta/cli/npm?current=2.0.3) returns version `2.0.1`, package `@opencode/cli`, GitHub ref `refs/heads/v2`.
- [`dev/cli/npm?current=0.0.0-dev-19593`](https://opencode.ai/update/api/dev/cli/npm?current=0.0.0-dev-19593) returns `0.0.0-dev-19593`.
- The latest aggregate endpoint lists both the NPM and standalone artifacts at 2.0.3.
- The beta aggregate endpoint lists AUR `0.0.0-beta-19507`, but NPM and standalone artifacts at 2.0.1.

NPM registry metadata:

- [`@opencode/cli/latest`](https://registry.npmjs.org/@opencode%2fcli/latest) is `2.0.3` and includes Windows x64, baseline x64, and arm64 optional packages at 2.0.3.
- [`@opencode/cli/beta`](https://registry.npmjs.org/@opencode%2fcli/beta) is `0.0.0-beta-19507` with the same Windows package layout.
- [`@opencode/cli/dev`](https://registry.npmjs.org/@opencode%2fcli/dev) is `0.0.0-dev-19593`.

## CLI Updater Behavior

Source: [`packages/cli/src/services/updater.ts`](https://raw.githubusercontent.com/anomalyco/opencode/v2/packages/cli/src/services/updater.ts)

- The release URL is `https://opencode.ai/update/api/{compiled channel}/{compiled artifact}/npm?current={compiled version}`.
- The response must contain `metadata.package`; the updater then constructs `{package}@{version}`.
- Installation methods are `curl`, `npm`, `pnpm`, `bun`, and `yarn`.
- The curl method downloads `https://opencode.ai/v2/install`, then runs `bash installer --version {version} --no-modify-path`.
- The package-manager commands are:
  - npm: `npm install --global [--force] {package}@{version}`
  - pnpm: `pnpm add --global --allow-build={package} {package}@{version}`
  - bun: `bun install --global --trust --cache-dir {temporary directory} {package}@{version}`
  - yarn: `yarn global add {package}@{version}`
- A package migration guard rejects pnpm/yarn upgrades when the detected installed package name differs from the update service package name.
- Curl is selected when the running executable is `$HOME/.opencode/bin/opencode` or `opencode.exe`; otherwise the updater probes global package-manager listings.

Source: [`packages/cli/src/services/updater-action.ts`](https://raw.githubusercontent.com/anomalyco/opencode/v2/packages/cli/src/services/updater-action.ts)

- `action()` validates both versions and suppresses only an invalid version, disabled policy, or identical release.
- It does not use semver ordering. A lower but different valid version is considered available.

Sources: [`packages/cli/src/commands/commands.ts`](https://raw.githubusercontent.com/anomalyco/opencode/v2/packages/cli/src/commands/commands.ts), [`packages/cli/src/commands/handlers/upgrade.ts`](https://raw.githubusercontent.com/anomalyco/opencode/v2/packages/cli/src/commands/handlers/upgrade.ts)

- The command is `upgrade`, aliased as `update`, with an optional version target and optional `--method`/`-m`.
- Without a target, the handler calls `updater.latest()`.
- It only skips when the returned version string equals the compiled `OPENCODE_VERSION`; it does not reject a downgrade.

## Installer And Windows Behavior

Source: [`install`](https://raw.githubusercontent.com/anomalyco/opencode/v2/install)

- The default install directory is `$HOME/.opencode/bin`.
- The no-version path explicitly requests `/update/api/beta/cli/npm`.
- The script supports `windows-x64` but rejects `windows-arm64` in its OS/architecture allowlist, despite the NPM package and release artifact containing Windows arm64 builds.
- Windows downloads an NPM tarball, extracts it, and runs `mv` from `package/bin/opencode.exe` to `$HOME/.opencode/bin/opencode.exe`.
- The installer creates `opencode2.cmd` as an alias that invokes `opencode.exe`.
- `--version` bypasses the update endpoint and requests the specified package version.

Source: [`packages/cli/script/postinstall.mjs`](https://raw.githubusercontent.com/anomalyco/opencode/v2/packages/cli/script/postinstall.mjs)

- The package postinstall selects the platform/architecture optional dependency, copies its native binary to the package's target `bin/opencode.exe`, and verifies it by running `--version`.
- If an existing target cannot be unlinked, the unlink error is ignored, but the subsequent link/copy can still fail. There is no delayed Windows replacement in this v2 script.

Sources: [`packages/cli/bin/opencode.cjs`](https://raw.githubusercontent.com/anomalyco/opencode/v2/packages/cli/bin/opencode.cjs), [`packages/cli/bin/opencode2.cjs`](https://raw.githubusercontent.com/anomalyco/opencode/v2/packages/cli/bin/opencode2.cjs), [`packages/cli/package.json`](https://raw.githubusercontent.com/anomalyco/opencode/v2/packages/cli/package.json)

- The wrapper resolves Windows native binaries as `opencode.exe`; `opencode2.cjs` delegates to the same wrapper.
- The published package metadata maps both `opencode` and `opencode2` to `bin/opencode.exe`.
- `OPENCODE_BIN_PATH` can override the wrapper's resolved executable, which can create a second-install/PATH mismatch if set.

## Publication And Service Rules

Sources: [`packages/script/src/index.ts`](https://raw.githubusercontent.com/anomalyco/opencode/v2/packages/script/src/index.ts), [`.github/workflows/publish.yml`](https://raw.githubusercontent.com/anomalyco/opencode/v2/.github/workflows/publish.yml), [`packages/cli/script/publish.ts`](https://raw.githubusercontent.com/anomalyco/opencode/v2/packages/cli/script/publish.ts)

- `OPENCODE_CHANNEL` takes precedence; bump/version releases resolve to `latest`; otherwise the current Git branch becomes the channel.
- The publish workflow sets ordinary `v2` pushes to `dev` and uses `latest` for v2 bump/version releases.
- NPM publication uses `--tag ${Script.channel}` and update artifacts use the same channel.

Source: [`services/update/src/index.ts`](https://raw.githubusercontent.com/anomalyco/opencode/v2/services/update/src/index.ts)

- The update service resolves `next` to `beta` and keeps channel artifacts independently active.
- Under the current source, `refs/heads/v2` is authorized for `dev` and `latest`; `refs/heads/beta` is authorized for `beta`.
- The active beta NPM/standalone rows currently carry `refs/heads/v2` and version 2.0.1, so they appear to be historical/stale channel data relative to the current publication rules.

## First-Party Windows Reports

- [#48558](https://github.com/anomalyco/opencode/issues/48558) was closed as resolved in 2.0.1. The bot states that the rename from `opencode2` to `opencode` landed before the Windows beta artifact changed, causing the installer to look for `opencode.exe` while the archive contained `opencode2.exe`. It also confirms `opencode2.cmd` is retained as an alias.
- [#37055](https://github.com/anomalyco/opencode/issues/37055) remains open and reports that the older curl upgrade printed success while Windows left the running executable unchanged because the `.exe` was locked.
- [#48368](https://github.com/anomalyco/opencode/pull/48368) is open and proposes a Windows-specific scheduled replacement after process exit. Its patch targets the legacy `packages/opencode` installer, not the current v2 `packages/cli` updater.
- [#47714](https://github.com/anomalyco/opencode/issues/47714) reports a v2 beta update prompt recommending an older prerelease; its bot comment links the same downgrade-detection root cause.
- [#42891](https://github.com/anomalyco/opencode/issues/42891) reports package/channel confusion after `pnpm update -g --latest`; a comment says reinstalling `@opencode/cli@beta` restores the update command.
- [#43368](https://github.com/anomalyco/opencode/issues/43368) documents an earlier postinstall failure where preview native package versions were not available in the registry.

## User-Side Confirmation

These commands distinguish channel selection from PATH/install replacement issues:

```powershell
opencode --version
opencode debug paths bin
where.exe opencode
Get-Command opencode -All
npm list -g @opencode/cli
pnpm list -g --depth=0 @opencode/cli
```

If the installed binary reports a beta channel or `opencode2` is resolving to a legacy path, the observed 2.0.1 result is expected from the current beta endpoint. If the command reports upgrade complete but the version remains unchanged, the Windows executable replacement/PATH path is the separate likely cause.

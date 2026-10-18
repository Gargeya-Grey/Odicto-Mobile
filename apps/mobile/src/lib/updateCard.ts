import type { UpdateStatus } from '../native/update';

export type UpdateCardText = { title: string; line: string };

/** The words on the "new version" card, or null when there is nothing to offer. */
export function describeUpdate(
  status: UpdateStatus | null,
): UpdateCardText | null {
  if (!status?.enabled || !status.available || !status.version) return null;
  const have = status.current ? `You have ${status.current}. ` : '';
  return {
    title: `Odicto ${status.version} is available`,
    line: `${have}Download it, open the file, and Android updates Odicto in place. Your keys and history stay.`,
  };
}

/** The line under "Check for updates" in Settings. */
export function updateSettingLine(enabled: boolean): string {
  return enabled
    ? 'When you open Odicto, about once an hour.'
    : 'Off. Odicto will not look for new versions.';
}

/** The line under "Check now": what the last look found, in plain words. */
export function checkResultLine(
  status: UpdateStatus,
  now: number,
  formatAgo: (timestamp: number, now: number) => string,
): string {
  if (!status.enabled) return 'Turn on Check for updates first.';
  if (status.failed)
    return status.checkedAt > 0
      ? `Could not reach GitHub just now. Last checked ${formatAgo(status.checkedAt, now)}.`
      : 'Could not reach GitHub. Check your connection and try again.';
  if (status.checkedAt <= 0) return 'Not checked yet.';
  const when = formatAgo(status.checkedAt, now);
  const ago = when === 'Just now' ? 'just now' : when;
  return status.available
    ? `Odicto ${status.version} is available. Checked ${ago}.`
    : `You have the latest version. Checked ${ago}.`;
}

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
    ? 'Once a day, when you open Odicto.'
    : 'Off. Odicto will not look for new versions.';
}

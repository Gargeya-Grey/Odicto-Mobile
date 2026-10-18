import { Capacitor, registerPlugin } from '@capacitor/core';

export type UpdateStatus = {
  /** The "Check for updates" setting. */
  enabled: boolean;
  /** A newer release than the installed one, not hidden with "Not now". */
  available: boolean;
  /** The newer version, such as "1.2.0". Null when none is on offer. */
  version: string | null;
  /** The installed version. */
  current: string;
};

const plugin = registerPlugin<{
  status(): Promise<UpdateStatus>;
  check(): Promise<UpdateStatus>;
  setEnabled(options: { enabled: boolean }): Promise<UpdateStatus>;
  dismiss(): Promise<UpdateStatus>;
  openDownload(): Promise<void>;
}>('OdictoUpdate');

export const updates = {
  supported: Capacitor.getPlatform() === 'android',
  /** What is already known; no network request. */
  status: () => plugin.status(),
  /** Asks for the newest release when a check is due (once a day); otherwise the same as `status`. */
  check: () => plugin.check(),
  setEnabled: (enabled: boolean) => plugin.setEnabled({ enabled }),
  dismiss: () => plugin.dismiss(),
  /** Opens the release page for the offered version in the browser. */
  openDownload: () => plugin.openDownload(),
};

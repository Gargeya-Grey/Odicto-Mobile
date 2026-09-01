// Foreground Service Manager: Keeps floating voice bar & background audio recording
// alive across long sessions using Screen Wake Lock, Web Notifications, and MediaSession APIs.

class ForegroundServiceManager {
  private wakeLock: any = null;
  private activeNotification: any = null;
  private isRunning: boolean = false;
  private notificationSupported: boolean = false;
  private wakeLockSupported: boolean = false;

  constructor() {
    this.notificationSupported = typeof window !== 'undefined' && 'Notification' in window;
    this.wakeLockSupported = typeof navigator !== 'undefined' && 'wakeLock' in navigator;
  }

  // Request notification permissions for persistent OS notifications
  async requestPermissions(): Promise<boolean> {
    if (!this.notificationSupported) return false;
    try {
      if (Notification.permission === 'granted') return true;
      if (Notification.permission !== 'denied') {
        const result = await Notification.requestPermission();
        return result === 'granted';
      }
    } catch (e) {
      console.warn('Notification permission request failed:', e);
    }
    return false;
  }

  get isSupported(): boolean {
    return this.wakeLockSupported || this.notificationSupported;
  }

  get hasNotificationPermission(): boolean {
    return this.notificationSupported && Notification.permission === 'granted';
  }

  // Start foreground service: Acquire wake lock, post OS notification, configure MediaSession
  async startForeground(options?: { title?: string; body?: string; isAi?: boolean }): Promise<void> {
    this.isRunning = true;

    // 1. Acquire Screen & CPU Wake Lock to prevent OS from sleeping or throttling background timers
    if (this.wakeLockSupported) {
      try {
        if (!this.wakeLock) {
          this.wakeLock = await (navigator as any).wakeLock.request('screen');
          this.wakeLock.addEventListener('release', () => {
            this.wakeLock = null;
          });
        }
      } catch (err) {
        console.warn('Screen WakeLock could not be acquired:', err);
      }
    }

    // 2. Set MediaSession metadata to elevate audio process priority in OS background scheduler
    if (typeof navigator !== 'undefined' && 'mediaSession' in navigator) {
      try {
        navigator.mediaSession.metadata = new MediaMetadata({
          title: options?.title || 'Odicto Voice Recording Active',
          artist: options?.isAi ? 'AI Assistant Mode' : 'Raw Groq Whisper STT',
          album: 'Odicto Foreground Service',
        });
        navigator.mediaSession.playbackState = 'playing';
      } catch (err) {
        console.warn('MediaSession configuration failed:', err);
      }
    }

    // 3. Post system-level persistent notification
    if (this.hasNotificationPermission) {
      try {
        const title = options?.title || 'Odicto Floating Voice • Recording Active';
        const body = options?.body || (options?.isAi 
          ? 'Continuous AI speech capture is running in the foreground.' 
          : 'Continuous dictation is running. Tap to return.');

        // Close previous if exists
        if (this.activeNotification) {
          try { this.activeNotification.close(); } catch {}
        }

        this.activeNotification = new Notification(title, {
          body,
          tag: 'odicto-foreground-recording',
          requireInteraction: true, // Keep notification pinned in Android / desktop shade
          silent: true,
        });

        this.activeNotification.onclick = () => {
          window.focus();
        };
      } catch (err) {
        console.warn('Foreground notification posting failed:', err);
      }
    }
  }

  // Stop foreground service: Release wake lock, dismiss notification, clear MediaSession
  async stopForeground(): Promise<void> {
    this.isRunning = false;

    // Release wake lock
    if (this.wakeLock) {
      try {
        await this.wakeLock.release();
      } catch {}
      this.wakeLock = null;
    }

    // Close notification
    if (this.activeNotification) {
      try {
        this.activeNotification.close();
      } catch {}
      this.activeNotification = null;
    }

    // Clear MediaSession
    if (typeof navigator !== 'undefined' && 'mediaSession' in navigator) {
      try {
        navigator.mediaSession.playbackState = 'none';
      } catch {}
    }
  }

  get isForegroundActive(): boolean {
    return this.isRunning;
  }
}

export const foregroundService = new ForegroundServiceManager();

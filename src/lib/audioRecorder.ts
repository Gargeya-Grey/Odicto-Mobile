// High-performance Mobile & Desktop Voice Recorder
// Engineered specifically for low-latency Groq Whisper STT & speech AI engines
// - Captures mono 16kHz-optimized speech streams (halves payload size & latency)
// - Negotiates optimal container: WebM Opus (Chromium/Android) or MP4/M4A AAC (iOS Safari)
// - Employs Web Audio AnalyserNode for silky non-blocking real-time visualizer

export interface AudioRecordResult {
  blob: Blob;
  base64: string;
  mimeType: string;
  durationMs: number;
  isTooShort: boolean;
  sizeBytes: number;
}

export class AudioRecorder {
  private mediaRecorder: MediaRecorder | null = null;
  private audioChunks: Blob[] = [];
  private stream: MediaStream | null = null;
  private audioContext: AudioContext | null = null;
  private analyser: AnalyserNode | null = null;
  private sourceNode: MediaStreamAudioSourceNode | null = null;
  private animFrameId: number | null = null;
  private mimeType: string = 'audio/webm';
  private startTime: number = 0;

  /**
   * Determine the most efficient speech-optimized audio container supported by the browser.
   * Priority:
   * 1. audio/webm;codecs=opus (Fastest, best compression for speech, 16-24kHz Opus)
   * 2. audio/mp4 (Native hardware AAC on iOS Safari & macOS WebKit)
   * 3. audio/aac
   * 4. audio/ogg;codecs=opus
   * 5. audio/webm
   */
  private static getOptimalSpeechMimeType(): string {
    if (typeof MediaRecorder === 'undefined' || !MediaRecorder.isTypeSupported) {
      return 'audio/webm';
    }

    const preferredCodecs = [
      'audio/webm;codecs=opus',
      'audio/mp4;codecs=mp4a.40.2',
      'audio/mp4',
      'audio/aac',
      'audio/ogg;codecs=opus',
      'audio/webm',
    ];

    for (const codec of preferredCodecs) {
      if (MediaRecorder.isTypeSupported(codec)) {
        return codec;
      }
    }

    return 'audio/webm';
  }

  async start(
    onAudioLevel?: (level: number, frequencyBands?: number[], timeDomain?: Uint8Array) => void
  ): Promise<boolean> {
    try {
      this.audioChunks = [];
      this.startTime = Date.now();

      // Acquire speech-optimized microphone stream:
      // - mono channel (channelCount: 1) to eliminate redundant stereo data
      // - 16000-24000Hz ideal sample rate matching Whisper's internal 16kHz mel filterbanks
      // - hardware voice processing flags for crisp speech isolation
      const constraints: MediaStreamConstraints = {
        audio: {
          channelCount: { ideal: 1 },
          sampleRate: { ideal: 16000 },
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          // Vendor-specific voice tuning enhancements
          ...(typeof window !== 'undefined'
            ? {
                googEchoCancellation: true,
                googAutoGainControl: true,
                googNoiseSuppression: true,
                googHighpassFilter: true,
              }
            : {}),
        } as any,
      };

      this.stream = await navigator.mediaDevices.getUserMedia(constraints);

      // Select optimal container
      this.mimeType = AudioRecorder.getOptimalSpeechMimeType();

      // Configure MediaRecorder with voice-optimized bit rate (32-48 kbps mono)
      // Standard 128 kbps is wasteful for speech; 32-48 kbps Opus/AAC retains 100% speech fidelity
      // while cutting mobile upload payload size and latency by ~70%.
      const recorderOptions: MediaRecorderOptions = {
        mimeType: this.mimeType,
        audioBitsPerSecond: 32000,
      };

      try {
        this.mediaRecorder = new MediaRecorder(this.stream, recorderOptions);
      } catch (optErr) {
        // Fallback without bitrate constraint if browser is strict
        console.warn('Fallback to default MediaRecorder options:', optErr);
        this.mediaRecorder = new MediaRecorder(this.stream);
      }

      if (this.mediaRecorder.mimeType) {
        this.mimeType = this.mediaRecorder.mimeType;
      }

      this.mediaRecorder.ondataavailable = (event: BlobEvent) => {
        if (event.data && event.data.size > 0) {
          this.audioChunks.push(event.data);
        }
      };

      // Start recording in continuous unfragmented mode to maintain unbroken container headers
      this.mediaRecorder.start();

      // Setup non-blocking audio level meter & multi-band analyzer for UI visualizer
      try {
        const AudioContextClass = window.AudioContext || (window as any).webkitAudioContext;
        if (AudioContextClass) {
          this.audioContext = new AudioContextClass();
          this.sourceNode = this.audioContext.createMediaStreamSource(this.stream);
          this.analyser = this.audioContext.createAnalyser();
          this.analyser.fftSize = 128;
          this.analyser.smoothingTimeConstant = 0.65;
          this.sourceNode.connect(this.analyser);

          const freqDataArray = new Uint8Array(this.analyser.frequencyBinCount);
          const timeDataArray = new Uint8Array(this.analyser.fftSize);

          const updateLevel = () => {
            if (this.analyser && onAudioLevel) {
              this.analyser.getByteFrequencyData(freqDataArray);
              this.analyser.getByteTimeDomainData(timeDataArray);

              let sum = 0;
              for (let i = 0; i < freqDataArray.length; i++) {
                sum += freqDataArray[i];
              }
              const avg = sum / freqDataArray.length;
              const normalized = Math.min(100, Math.round((avg / 128) * 100));

              // Compute 10 normalized sub-bands for visualizer
              const bandsCount = 10;
              const step = Math.floor(freqDataArray.length / bandsCount);
              const bands: number[] = [];
              for (let b = 0; b < bandsCount; b++) {
                let bSum = 0;
                const startIdx = b * step;
                const endIdx = Math.min(freqDataArray.length, (b + 1) * step);
                for (let j = startIdx; j < endIdx; j++) {
                  bSum += freqDataArray[j];
                }
                const bAvg = bSum / (endIdx - startIdx || 1);
                bands.push(Math.min(100, Math.round((bAvg / 255) * 100)));
              }

              onAudioLevel(normalized, bands, timeDataArray);
            }
            this.animFrameId = requestAnimationFrame(updateLevel);
          };
          updateLevel();
        }
      } catch (audioCtxErr) {
        console.warn('AudioContext visualizer init warning:', audioCtxErr);
      }

      return true;
    } catch (err) {
      console.error('AudioRecorder failed to start:', err);
      return false;
    }
  }

  async stop(): Promise<AudioRecordResult | null> {
    // Stop visualizer animation
    if (this.animFrameId) {
      cancelAnimationFrame(this.animFrameId);
      this.animFrameId = null;
    }

    // Clean up Web Audio nodes
    if (this.sourceNode) {
      try { this.sourceNode.disconnect(); } catch {}
      this.sourceNode = null;
    }
    if (this.audioContext) {
      try { await this.audioContext.close(); } catch {}
      this.audioContext = null;
    }

    const durationMs = this.startTime > 0 ? Date.now() - this.startTime : 0;

    return new Promise((resolve) => {
      if (!this.mediaRecorder || this.mediaRecorder.state === 'inactive') {
        if (this.stream) {
          this.stream.getTracks().forEach((t) => t.stop());
          this.stream = null;
        }
        return resolve(null);
      }

      this.mediaRecorder.onstop = () => {
        // Stop all microphone tracks immediately
        if (this.stream) {
          try {
            this.stream.getTracks().forEach((t) => t.stop());
          } catch {}
          this.stream = null;
        }

        if (this.audioChunks.length === 0) {
          return resolve(null);
        }

        const audioBlob = new Blob(this.audioChunks, { type: this.mimeType });
        this.audioChunks = [];

        // Check if recording is too short (< 250ms or tiny file size)
        const isTooShort = durationMs < 250 || audioBlob.size < 400;

        // Convert Blob to Base64
        const reader = new FileReader();
        reader.onloadend = () => {
          const resultStr = reader.result as string;
          const base64Data = resultStr.includes(',') ? resultStr.split(',')[1] : resultStr;
          resolve({
            blob: audioBlob,
            base64: base64Data,
            mimeType: this.mimeType,
            durationMs,
            isTooShort,
            sizeBytes: audioBlob.size,
          });
        };
        reader.onerror = () => resolve(null);
        reader.readAsDataURL(audioBlob);
      };

      try {
        if (this.mediaRecorder.state !== 'inactive') {
          this.mediaRecorder.stop();
        } else {
          resolve(null);
        }
      } catch (err) {
        console.error('Error stopping MediaRecorder:', err);
        resolve(null);
      }
    });
  }

  get isRecording(): boolean {
    return this.mediaRecorder !== null && this.mediaRecorder.state === 'recording';
  }

  getCurrentMimeType(): string {
    return this.mimeType;
  }
}

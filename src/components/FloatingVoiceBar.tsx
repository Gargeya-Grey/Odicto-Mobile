import React, { useState, useRef, useEffect } from 'react';
import { 
  Mic, 
  Sparkles, 
  Lock, 
  Unlock,
  Send, 
  Trash2, 
  Check, 
  SlidersHorizontal,
  Loader2,
  Maximize2,
  Minimize2,
  GripHorizontal,
  CheckCircle2,
  AlertCircle
} from 'lucide-react';
import { AppConfig, OutputFilter } from '../types';
import { sounds, haptics } from '../lib/audio';
import { foregroundService } from '../lib/foregroundService';
import { AudioRecorder } from '../lib/audioRecorder';
import { AudioWaveformVisualizer } from './AudioWaveformVisualizer';
import { FILTER_PRESETS } from '../lib/constants';
import { copyToClipboard } from '../lib/clipboard';
import { fetchWithTimeoutAndRetry } from '../lib/network';

interface FloatingVoiceBarProps {
  config: AppConfig | null;
  onTextDelivered?: (text: string, raw: string, mode: 'raw' | 'ai') => void;
  activeFilter?: OutputFilter;
  onFilterChange?: (filter: OutputFilter) => void;
}

export const FloatingVoiceBar: React.FC<FloatingVoiceBarProps> = ({
  config,
  onTextDelivered,
  activeFilter = 'clean_speech',
  onFilterChange,
}) => {
  const [isAiMode, setIsAiMode] = useState<boolean>(true);
  const [isHolding, setIsHolding] = useState<boolean>(false);
  const [isLocked, setIsLocked] = useState<boolean>(false);
  const [slideProgress, setSlideProgress] = useState<number>(0); // 0 to 1
  const [recordingSeconds, setRecordingSeconds] = useState<number>(0);
  const [audioLevel, setAudioLevel] = useState<number>(0);
  const [frequencyBands, setFrequencyBands] = useState<number[]>([0, 0, 0, 0, 0, 0, 0, 0]);
  const [statusState, setStatusState] = useState<'idle' | 'recording' | 'transcribing' | 'refining' | 'pasting' | 'done' | 'error'>('idle');
  const [statusMessage, setStatusMessage] = useState<string>('');
  const [isMinimized, setIsMinimized] = useState<boolean>(false);
  const [showFilterPicker, setShowFilterPicker] = useState<boolean>(false);
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  // Position state for floating dragging
  const [position, setPosition] = useState<{ x: number; y: number } | null>(null);
  const [isDraggingBar, setIsDraggingBar] = useState<boolean>(false);

  const audioRecorderRef = useRef<AudioRecorder>(new AudioRecorder());
  const timerIntervalRef = useRef<any>(null);
  const startPointerYRef = useRef<number>(0);
  const startPointerXRef = useRef<number>(0);
  const isHoldingRef = useRef<boolean>(false);
  const isLockedRef = useRef<boolean>(false);
  const dragStartPosRef = useRef<{ x: number; y: number; posX: number; posY: number }>({ x: 0, y: 0, posX: 0, posY: 0 });
  const barContainerRef = useRef<HTMLDivElement>(null);
  const lastTapTimeRef = useRef<number>(0);

  // Forgiving magnetic lock distance (34px for instant thumb slide)
  const LOCK_DISTANCE_PX = 34;

  // Audio timer
  useEffect(() => {
    if (statusState === 'recording') {
      setRecordingSeconds(0);
      timerIntervalRef.current = setInterval(() => {
        setRecordingSeconds((s) => s + 1);
      }, 1000);
    } else {
      if (timerIntervalRef.current) {
        clearInterval(timerIntervalRef.current);
        timerIntervalRef.current = null;
      }
    }
    return () => {
      if (timerIntervalRef.current) clearInterval(timerIntervalRef.current);
    };
  }, [statusState]);

  // Start recording
  const startRecording = async (lockImmediately = false) => {
    try {
      sounds.playStartCue();
      haptics.start();
      setStatusState('recording');
      setStatusMessage(isAiMode ? 'AI Dictation Listening...' : 'Whisper Capturing...');
      
      if (lockImmediately) {
        setIsLocked(true);
        isLockedRef.current = true;
        haptics.lock();
      }

      foregroundService.startForeground({
        title: isAiMode ? 'Odicto AI • Recording' : 'Odicto Voice • Recording',
        body: 'System dictation active.',
        isAi: isAiMode,
      });

      await audioRecorderRef.current.start((level, bands) => {
        setAudioLevel(level);
        if (bands) setFrequencyBands(bands);
      });
    } catch (err) {
      console.error('Recording start failed:', err);
      foregroundService.stopForeground();
      haptics.error();
      setStatusState('error');
      setStatusMessage('Mic access failed');
      setTimeout(() => setStatusState('idle'), 2500);
    }
  };

  // Direct toggle for Hands-Free Lock (Tap lock icon anytime)
  const handleToggleLock = () => {
    if (statusState !== 'recording') {
      // Start directly locked!
      startRecording(true);
    } else {
      if (isLocked) {
        // Unlock and finish
        finishAndDeliver();
      } else {
        // Lock currently running recording
        setIsLocked(true);
        isLockedRef.current = true;
        haptics.lock();
        sounds.playStartCue();
      }
    }
  };

  // Stop recording and paste text into active editor / clipboard
  const finishAndDeliver = async () => {
    setIsHolding(false);
    setIsLocked(false);
    isHoldingRef.current = false;
    isLockedRef.current = false;
    setSlideProgress(0);
    setAudioLevel(0);
    setFrequencyBands([0, 0, 0, 0, 0, 0, 0, 0]);
    sounds.playStopCue();
    haptics.stop();

    foregroundService.stopForeground();

    setStatusState('transcribing');
    setStatusMessage('Transcribing...');

    try {
      const audioData = await audioRecorderRef.current.stop();
      if (!audioData || !audioData.base64 || audioData.isTooShort) {
        setStatusState('error');
        setStatusMessage('Hold to speak');
        setTimeout(() => setStatusState('idle'), 1800);
        return;
      }

      // Step 1: Transcribe via Groq Whisper API (with adaptive timeout and retry)
      const transcribeRes = await fetchWithTimeoutAndRetry('/api/transcribe', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          audioBase64: audioData.base64,
          mimeType: audioData.mimeType,
        }),
        timeoutMs: 14000,
        retries: 1,
        onRetry: (attempt) => {
          setStatusMessage(`Retrying connection (${attempt})...`);
        },
      });

      const transcribeJson = await transcribeRes.json();
      if (!transcribeJson.ok || !transcribeJson.text) {
        throw new Error(transcribeJson.message || transcribeJson.error || 'Speech transcription failed');
      }

      const rawTranscript = transcribeJson.text.trim();
      let finalOutput = rawTranscript;

      // Step 2: AI Refinement (if AI mode is on)
      if (isAiMode) {
        setStatusState('refining');
        setStatusMessage('Formatting speech...');

        try {
          const refineRes = await fetchWithTimeoutAndRetry('/api/refine', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              text: rawTranscript,
              filter: activeFilter,
            }),
            timeoutMs: 15000,
            retries: 1,
            onRetry: () => {
              setStatusMessage('Retrying AI formatting...');
            },
          });

          const refineJson = await refineRes.json();
          if (refineJson.ok && refineJson.result) {
            finalOutput = refineJson.result.trim();
          }
        } catch (refineErr) {
          console.warn('AI refinement timeout/error, using raw transcript:', refineErr);
        }
      }

      // Step 3: Automatically paste/insert into active cursor or editor
      deliverTextToActiveDestination(finalOutput, rawTranscript);

      const isAutoCopy = config?.AUTO_COPY_CLIPBOARD ?? true;
      sounds.playCompleteCue();
      setStatusState('done');
      setStatusMessage(isAutoCopy ? 'Inserted & Copied' : 'Inserted');
      setTimeout(() => {
        setStatusState('idle');
        setStatusMessage('');
      }, 1500);

      if (onTextDelivered) {
        onTextDelivered(finalOutput, rawTranscript, isAiMode ? 'ai' : 'raw');
      }
    } catch (err: any) {
      console.error('Processing failure:', err);
      haptics.error();
      setStatusState('error');
      setStatusMessage(err.message || 'Processing failed');
      setTimeout(() => {
        setStatusState('idle');
        setStatusMessage('');
      }, 3000);
    }
  };

  // Cancel recording and discard
  const cancelRecording = () => {
    setIsHolding(false);
    setIsLocked(false);
    isHoldingRef.current = false;
    isLockedRef.current = false;
    setSlideProgress(0);
    setAudioLevel(0);
    setFrequencyBands([0, 0, 0, 0, 0, 0, 0, 0]);
    sounds.playStopCue();
    haptics.stop();
    foregroundService.stopForeground();
    audioRecorderRef.current.stop().catch(() => {});
    setStatusState('idle');
    setStatusMessage('');
  };

  // Deliver text to active cursor, clipboard & broadcast event
  const deliverTextToActiveDestination = async (text: string, raw: string) => {
    const isAutoCopy = config?.AUTO_COPY_CLIPBOARD ?? true;

    // 1. Copy to clipboard if enabled in settings
    if (isAutoCopy) {
      copyToClipboard(text).catch((err) => {
        console.warn('Auto-copy to clipboard failed:', err);
      });
    }

    // 2. Insert into currently focused input or textarea if present
    const activeEl = document.activeElement as HTMLInputElement | HTMLTextAreaElement;
    if (activeEl && (activeEl.tagName === 'INPUT' || activeEl.tagName === 'TEXTAREA')) {
      const start = activeEl.selectionStart || 0;
      const end = activeEl.selectionEnd || 0;
      const val = activeEl.value;
      activeEl.value = val.substring(0, start) + text + val.substring(end);
      activeEl.selectionStart = activeEl.selectionEnd = start + text.length;
      activeEl.dispatchEvent(new Event('input', { bubbles: true }));
    }

    // 3. Dispatch global synthetic event for sandbox / preview editors
    window.dispatchEvent(
      new CustomEvent('odicto-synthetic-paste', {
        detail: { text, rawTranscript: raw, isAi: isAiMode, filter: activeFilter },
      })
    );

    // 4. Show visual confirmation toast
    const shortText = text.length > 36 ? text.substring(0, 36) + '...' : text;
    setToastMessage(
      isAutoCopy
        ? `Copied: "${shortText}"`
        : `Inserted: "${shortText}"`
    );
    setTimeout(() => setToastMessage(null), 3000);
  };

  // Pointer event handlers for Hold-to-Talk and Magnetic Slide-to-Lock
  const handlePointerDown = (e: React.PointerEvent) => {
    e.preventDefault();
    if (statusState === 'transcribing' || statusState === 'refining') return;

    // Check for quick double-tap to lock
    const now = Date.now();
    if (now - lastTapTimeRef.current < 350) {
      lastTapTimeRef.current = 0;
      if (statusState === 'recording') {
        setIsLocked(true);
        isLockedRef.current = true;
        haptics.lock();
      } else {
        startRecording(true);
      }
      return;
    }
    lastTapTimeRef.current = now;

    if (isLocked) {
      // If currently locked, tapping the microphone releases and pastes
      finishAndDeliver();
      return;
    }

    setIsHolding(true);
    isHoldingRef.current = true;
    startPointerYRef.current = e.clientY;
    startPointerXRef.current = e.clientX;
    setSlideProgress(0);
    startRecording(false);

    try {
      (e.target as HTMLElement).setPointerCapture(e.pointerId);
    } catch {}
  };

  const handlePointerMove = (e: React.PointerEvent) => {
    if (!isHoldingRef.current || isLockedRef.current) return;

    // Support both sliding upward (deltaY) and sliding towards the lock button on the left (deltaX)
    const deltaY = startPointerYRef.current - e.clientY; // Positive when moving upward
    const deltaX = startPointerXRef.current - e.clientX; // Positive when moving left towards lock
    const slideDistance = Math.max(
      deltaY, 
      deltaX, 
      Math.hypot(Math.max(0, deltaY), Math.max(0, deltaX))
    );

    if (slideDistance > 0) {
      const progress = Math.min(1, slideDistance / LOCK_DISTANCE_PX);
      setSlideProgress(progress);

      if (progress >= 0.75 && !isLockedRef.current) {
        // Locked state achieved with magnetic snap!
        setIsLocked(true);
        isLockedRef.current = true;
        haptics.lock();
        sounds.playStartCue();
      }
    } else {
      setSlideProgress(0);
    }
  };

  const handlePointerUp = (e: React.PointerEvent) => {
    if (!isHoldingRef.current) return;
    try {
      (e.target as HTMLElement).releasePointerCapture(e.pointerId);
    } catch {}

    if (isLockedRef.current) {
      // Released after locking: keep recording continuously hands-free
      setIsHolding(false);
      isHoldingRef.current = false;
      return;
    }

    // Normal hold release: finish and deliver
    finishAndDeliver();
  };

  // Floating Bar Drag Handlers
  const handleDragStart = (e: React.PointerEvent) => {
    e.preventDefault();
    setIsDraggingBar(true);
    const rect = barContainerRef.current?.getBoundingClientRect();
    dragStartPosRef.current = {
      x: e.clientX,
      y: e.clientY,
      posX: rect ? rect.left : 0,
      posY: rect ? rect.top : 0,
    };
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
  };

  const handleDragMove = (e: React.PointerEvent) => {
    if (!isDraggingBar) return;
    const deltaX = e.clientX - dragStartPosRef.current.x;
    const deltaY = e.clientY - dragStartPosRef.current.y;
    const newX = Math.max(10, Math.min(window.innerWidth - 200, dragStartPosRef.current.posX + deltaX));
    const newY = Math.max(50, Math.min(window.innerHeight - 100, dragStartPosRef.current.posY + deltaY));
    setPosition({ x: newX, y: newY });
  };

  const handleDragEnd = (e: React.PointerEvent) => {
    setIsDraggingBar(false);
    try {
      (e.target as HTMLElement).releasePointerCapture(e.pointerId);
    } catch {}
  };

  const formatTimer = (sec: number) => {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  return (
    <>
      {/* Toast confirmation message */}
      {toastMessage && (
        <div 
          id="floating-voice-toast"
          className="fixed top-5 left-1/2 -translate-x-1/2 z-50 bg-[#141622]/98 border border-[rgba(255,255,255,0.18)] text-[#f3f4f6] text-xs px-4 py-2 rounded-full shadow-2xl backdrop-blur-[24px] flex items-center gap-2 animate-in fade-in slide-in-from-top-3 duration-200 max-w-[90vw] truncate font-mori"
        >
          <CheckCircle2 className="w-3.5 h-3.5 text-[#10b981] shrink-0" />
          <span className="truncate">{toastMessage}</span>
        </div>
      )}

      {/* Floating Bar Container */}
      <div
        ref={barContainerRef}
        id="floating-voice-bar"
        style={
          position
            ? { position: 'fixed', left: `${position.x}px`, top: `${position.y}px`, zIndex: 50 }
            : { position: 'fixed', bottom: '72px', left: '50%', transform: 'translateX(-50%)', zIndex: 50 }
        }
        className="transition-all select-none touch-none font-mori"
      >
        {/* Minimized Floating Bubble */}
        {isMinimized ? (
          <div className="flex items-center gap-2 bg-[#11131c]/95 border border-[rgba(255,255,255,0.12)] p-1.5 rounded-full shadow-2xl backdrop-blur-[24px]">
            <button
              id="minimized-voice-hold-btn"
              onPointerDown={handlePointerDown}
              onPointerMove={handlePointerMove}
              onPointerUp={handlePointerUp}
              className={`w-12 h-12 rounded-full flex items-center justify-center font-bold transition-all overflow-hidden relative shrink-0 ${
                statusState === 'recording'
                  ? isLocked
                    ? 'bg-[#10b981] text-[#090a0f] ring-4 ring-[#10b981]/30 glow-lock-active'
                    : isAiMode
                    ? 'bg-[#f3f4f6] text-[#090a0f] ring-4 ring-white/30 glow-active'
                    : 'bg-[#60a5fa] text-[#090a0f] ring-4 ring-[#60a5fa]/30'
                  : 'bg-[#f3f4f6] text-[#090a0f] hover:bg-white'
              }`}
              title="Hold to Talk / Tap to Finish"
            >
              {statusState === 'recording' ? (
                <AudioWaveformVisualizer
                  isRecording={true}
                  audioLevel={audioLevel}
                  frequencyBands={frequencyBands}
                  isAi={isAiMode}
                  embedded={true}
                />
              ) : (
                <Mic className="w-5 h-5" />
              )}
            </button>
            <button
              id="expand-floating-bar-btn"
              onClick={() => setIsMinimized(false)}
              className="p-2 text-[#9ca3af] hover:text-[#f3f4f6] shrink-0"
              title="Expand Voice Bar"
            >
              <Maximize2 className="w-4 h-4" />
            </button>
          </div>
        ) : (
          /* Full Floating Voice Bar */
          <div className="relative flex flex-col items-center">
            
            {/* Magnetic Slide-to-Lock Indicator (Visible while thumb is held down) */}
            {isHolding && !isLocked && (
              <div 
                id="magnetic-slide-lock-track"
                className="absolute -top-16 flex flex-col items-center gap-1.5 px-3 py-2 bg-[#141622]/98 border border-[#10b981]/40 rounded-2xl backdrop-blur-[24px] shadow-2xl animate-in fade-in zoom-in-95 duration-150"
              >
                <div className="flex items-center gap-2">
                  <div className={`p-1.5 rounded-full transition-all duration-150 flex items-center justify-center ${
                    slideProgress >= 0.7 
                      ? 'bg-[#10b981] text-[#090a0f] scale-125 shadow-lg shadow-[#10b981]/50' 
                      : 'bg-[#10b981]/20 text-[#10b981] animate-pulse ring-2 ring-[#10b981]/40'
                  }`}>
                    <Lock className="w-3.5 h-3.5" />
                  </div>
                  <span className={`font-mono text-[10px] uppercase tracking-wider whitespace-nowrap ${
                    slideProgress >= 0.7 ? 'text-[#10b981] font-bold' : 'text-[#f3f4f6]'
                  }`}>
                    {slideProgress >= 0.7 ? '🔒 Locked!' : 'Slide to Lock'}
                  </span>
                </div>
                
                {/* Magnetic Progress Beam */}
                <div className="w-24 h-1.5 bg-[#1f2333] rounded-full overflow-hidden p-0.5">
                  <div 
                    className="h-full bg-[#10b981] rounded-full transition-all duration-75"
                    style={{ width: `${Math.round(slideProgress * 100)}%` }}
                  />
                </div>
              </div>
            )}

            {/* Main Floating Dock */}
            <div className={`flex items-center gap-2 px-3 py-2 rounded-2xl backdrop-blur-[24px] shadow-2xl border transition-all duration-200 ${
              isLocked && statusState === 'recording'
                ? 'bg-[#11131c]/98 border-[#10b981]/60 ring-1 ring-[#10b981]/30'
                : statusState === 'recording'
                ? 'bg-[#11131c]/98 border-[rgba(255,255,255,0.3)]'
                : 'bg-[#11131c]/95 border-[rgba(255,255,255,0.12)]'
            }`}>
              
              {/* Drag Handle */}
              <div
                id="floating-bar-drag-handle"
                onPointerDown={handleDragStart}
                onPointerMove={handleDragMove}
                onPointerUp={handleDragEnd}
                className="cursor-grab active:cursor-grabbing p-1 text-[#6b7280] hover:text-[#9ca3af] transition-colors shrink-0"
                title="Drag floating bar anywhere"
              >
                <GripHorizontal className="w-3.5 h-3.5" />
              </div>

              {/* Status / Filter Section */}
              <div className="w-[102px] flex items-center justify-start shrink-0">
                {statusState === 'recording' ? (
                  /* Active Recording State */
                  <div className="flex items-center gap-1.5">
                    <span className="relative flex h-2 w-2 shrink-0">
                      <span className={`animate-ping absolute inline-flex h-full w-full rounded-full opacity-75 ${
                        isLocked ? 'bg-[#10b981]' : 'bg-[#60a5fa]'
                      }`}></span>
                      <span className={`relative inline-flex rounded-full h-2 w-2 ${
                        isLocked ? 'bg-[#10b981]' : 'bg-[#60a5fa]'
                      }`}></span>
                    </span>
                    <span className={`text-xs font-mono font-medium tracking-wider ${
                      isLocked ? 'text-[#10b981]' : 'text-[#f3f4f6]'
                    }`}>
                      {formatTimer(recordingSeconds)}
                    </span>
                    {isLocked && (
                      <span className="font-mono text-[8px] font-bold px-1 py-0.5 rounded bg-[#10b981]/20 text-[#10b981] border border-[#10b981]/30 flex items-center gap-0.5 ml-0.5">
                        <Lock className="w-2.5 h-2.5" />
                        <span>LOCKED</span>
                      </span>
                    )}
                  </div>
                ) : statusState === 'transcribing' || statusState === 'refining' ? (
                  /* Processing State */
                  <div className="flex items-center gap-1.5">
                    <Loader2 className="w-3.5 h-3.5 text-[#9ca3af] animate-spin shrink-0" />
                    <span className="font-mono text-[9px] uppercase tracking-wider text-[#9ca3af] truncate max-w-[80px]">
                      {statusState === 'transcribing' ? 'Listening' : 'Refining'}
                    </span>
                  </div>
                ) : statusState === 'done' ? (
                  /* Done State */
                  <div className="flex items-center gap-1.5">
                    <CheckCircle2 className="w-3.5 h-3.5 text-[#10b981] shrink-0" />
                    <span className="font-mono text-[9px] uppercase tracking-wider text-[#f3f4f6] truncate max-w-[80px]">
                      Pasted
                    </span>
                  </div>
                ) : statusState === 'error' ? (
                  /* Error State */
                  <div className="flex items-center gap-1.5">
                    <AlertCircle className="w-3.5 h-3.5 text-[#ef4444] shrink-0" />
                    <span className="font-mono text-[9px] uppercase tracking-wider text-[#ef4444] truncate max-w-[80px]">
                      Error
                    </span>
                  </div>
                ) : (
                  /* Idle Quick Filter Button */
                  <button
                    id="filter-picker-quick-btn"
                    onClick={() => setShowFilterPicker(!showFilterPicker)}
                    className="flex items-center gap-1 px-2 py-1 rounded-lg bg-[#181b26] border border-[rgba(255,255,255,0.08)] hover:border-[rgba(255,255,255,0.18)] text-[11px] text-[#9ca3af] transition-colors max-w-full truncate"
                    title="Change preset filter"
                  >
                    <SlidersHorizontal className="w-3 h-3 text-[#9ca3af] shrink-0" />
                    <span className="capitalize truncate text-[10px]">
                      {activeFilter.replace('_', ' ')}
                    </span>
                  </button>
                )}
              </div>

              {/* AI MODE TOGGLE BUTTON */}
              <button
                id="floating-ai-toggle-btn"
                type="button"
                onClick={() => setIsAiMode(!isAiMode)}
                className={`relative px-2 py-1.5 rounded-lg font-mono text-[10px] uppercase tracking-[0.08em] font-medium flex items-center gap-1 transition-all duration-200 shrink-0 ${
                  isAiMode
                    ? 'bg-[#f3f4f6] text-[#090a0f] font-semibold shadow-sm'
                    : 'bg-[#181b26] border border-[rgba(255,255,255,0.08)] text-[#9ca3af] hover:text-[#f3f4f6]'
                }`}
                title={isAiMode ? 'AI Formatting Active' : 'Raw Audio Mode'}
              >
                <Sparkles className="w-3 h-3" />
                <span>AI</span>
              </button>

              {/* SWIPE-TO-LOCK BUTTON / HANDS-FREE LOCK TOGGLE */}
              <button
                id="floating-quick-lock-btn"
                type="button"
                onClick={handleToggleLock}
                className={`p-2 rounded-xl transition-all duration-150 flex items-center justify-center shrink-0 border relative ${
                  isLocked
                    ? 'bg-[#10b981] text-[#090a0f] border-[#10b981] shadow-md shadow-[#10b981]/30 font-bold scale-105'
                    : isHolding
                    ? 'bg-[#10b981]/25 border-[#10b981] text-[#10b981] ring-2 ring-[#10b981]/50 scale-110 animate-pulse'
                    : statusState === 'recording'
                    ? 'bg-[#181b26] border-[#10b981]/60 text-[#10b981] hover:bg-[#10b981]/20'
                    : 'bg-[#181b26] border-[rgba(255,255,255,0.08)] text-[#9ca3af] hover:text-[#f3f4f6] hover:border-[rgba(255,255,255,0.2)]'
                }`}
                title={
                  isLocked 
                    ? 'Hands-Free Locked (Click to finish & paste)' 
                    : isHolding 
                    ? 'Slide here to Lock hands-free recording!' 
                    : 'Lock hands-free recording'
                }
              >
                {isLocked ? (
                  <Lock className="w-3.5 h-3.5" />
                ) : isHolding ? (
                  <Lock className="w-3.5 h-3.5 animate-bounce" />
                ) : (
                  <Unlock className="w-3.5 h-3.5" />
                )}
              </button>

              {/* PRIMARY MICROPHONE / HOLD-TO-TALK / CLICK-TO-RELEASE BUTTON */}
              <div className="relative shrink-0 flex items-center justify-center">
                {isLocked ? (
                  /* When Locked: Click Microphone again to Release and Paste! */
                  <div className="flex items-center gap-1.5">
                    <button
                      id="floating-cancel-recording-btn"
                      onClick={cancelRecording}
                      className="w-8 h-8 rounded-xl bg-[#181b26] hover:bg-[#252a3b] border border-[rgba(255,255,255,0.12)] flex items-center justify-center text-[#9ca3af] hover:text-[#ef4444] transition-colors"
                      title="Discard recording"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                    
                    <button
                      id="floating-locked-stop-send-btn"
                      onClick={finishAndDeliver}
                      className="h-10 px-3.5 rounded-xl bg-[#10b981] hover:bg-[#059669] text-[#090a0f] font-semibold text-xs flex items-center gap-2 active:scale-95 transition-all shadow-lg shadow-[#10b981]/30 ring-2 ring-[#10b981]/50"
                      title="Click microphone to release and paste text"
                    >
                      <Mic className="w-4 h-4 text-[#090a0f] shrink-0" />
                      <div className="w-7 overflow-hidden flex items-center justify-center">
                        <AudioWaveformVisualizer
                          isRecording={true}
                          audioLevel={audioLevel}
                          frequencyBands={frequencyBands}
                          isAi={isAiMode}
                          embedded={true}
                        />
                      </div>
                      <span className="text-[10px] font-mono uppercase tracking-wider font-bold">Release & Paste</span>
                    </button>
                  </div>
                ) : (
                  /* Normal Hold-to-Talk Thumb Button: Waveform appears inside */
                  <button
                    id="floating-thumb-hold-mic-btn"
                    type="button"
                    onPointerDown={handlePointerDown}
                    onPointerMove={handlePointerMove}
                    onPointerUp={handlePointerUp}
                    className={`w-12 h-12 rounded-full flex items-center justify-center font-bold transition-all duration-150 overflow-hidden relative shrink-0 ${
                      statusState === 'recording'
                        ? 'bg-[#f3f4f6] text-[#090a0f] ring-4 ring-white/30 glow-active scale-105'
                        : 'bg-[#f3f4f6] text-[#090a0f] hover:bg-white active:scale-95 shadow-md'
                    }`}
                    title="Hold thumb to speak • Slide towards lock to lock hands-free"
                  >
                    {statusState === 'recording' ? (
                      <AudioWaveformVisualizer
                        isRecording={true}
                        audioLevel={audioLevel}
                        frequencyBands={frequencyBands}
                        isAi={isAiMode}
                        embedded={true}
                      />
                    ) : (
                      <Mic className="w-5 h-5" />
                    )}
                  </button>
                )}
              </div>

              {/* Minimize button */}
              <button
                id="floating-minimize-btn"
                onClick={() => setIsMinimized(true)}
                className="p-1 text-[#6b7280] hover:text-[#9ca3af] transition-colors shrink-0"
                title="Minimize floating bar"
              >
                <Minimize2 className="w-3.5 h-3.5" />
              </button>
            </div>

            {/* Quick Filter Selection Dropdown */}
            {showFilterPicker && (
              <div 
                id="floating-filter-dropdown"
                className="absolute bottom-16 bg-[#141622]/98 border border-[rgba(255,255,255,0.14)] rounded-2xl p-2.5 shadow-2xl backdrop-blur-[24px] w-64 z-50 animate-in fade-in zoom-in-95 duration-100"
              >
                <div className="font-mono text-[9px] font-semibold text-[#9ca3af] px-2 py-1 mb-1 uppercase tracking-[0.14em] flex items-center gap-1.5">
                  <SlidersHorizontal className="w-3.5 h-3.5 text-[#f3f4f6]" />
                  <span>Formatting Directives</span>
                </div>
                <div className="space-y-1">
                  {FILTER_PRESETS.map((preset) => (
                    <button
                      key={preset.id}
                      onClick={() => {
                        if (onFilterChange) onFilterChange(preset.id);
                        setShowFilterPicker(false);
                      }}
                      className={`w-full text-left px-2.5 py-1.5 rounded-xl text-xs flex items-center justify-between transition-colors ${
                        activeFilter === preset.id
                          ? 'bg-[#f3f4f6] text-[#090a0f] font-semibold'
                          : 'text-[#9ca3af] hover:text-[#f3f4f6] hover:bg-[#1f2333]'
                      }`}
                    >
                      <div className="truncate">
                        <div className="font-medium truncate">{preset.label}</div>
                        <div className="text-[9px] opacity-75 truncate">{preset.shortDesc}</div>
                      </div>
                      {activeFilter === preset.id && <Check className="w-3.5 h-3.5 shrink-0 ml-1" />}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </>
  );
};

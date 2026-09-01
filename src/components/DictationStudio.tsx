import React, { useState, useEffect, useRef } from 'react';
import { 
  Mic, 
  Sparkles, 
  Copy, 
  Trash2, 
  RotateCcw, 
  Check, 
  ChevronRight, 
  Volume2, 
  VolumeX, 
  Loader2,
  Clock,
  BrainCircuit,
  SlidersHorizontal,
  FileText,
  CheckCircle2,
  ExternalLink,
  Shield,
  Layers
} from 'lucide-react';
import { AppConfig, DictationEntry, LlmProvider, OutputFilter } from '../types';
import { sounds, haptics } from '../lib/audio';
import { foregroundService } from '../lib/foregroundService';
import { AudioRecorder } from '../lib/audioRecorder';
import { AudioWaveformVisualizer } from './AudioWaveformVisualizer';
import { copyToClipboard } from '../lib/clipboard';
import { PROVIDERS, FILTER_PRESETS } from '../lib/constants';
import { fetchWithTimeoutAndRetry } from '../lib/network';

interface DictationStudioProps {
  config: AppConfig | null;
  onNavigateToSetup: () => void;
  onNavigateToFilters: () => void;
  onToggleMultiTurn: (enabled: boolean) => void;
  onSwitchProvider: (provider: LlmProvider, model?: string) => void;
}

export const DictationStudio: React.FC<DictationStudioProps> = ({
  config,
  onNavigateToSetup,
  onNavigateToFilters,
  onToggleMultiTurn,
  onSwitchProvider,
}) => {
  const [history, setHistory] = useState<DictationEntry[]>([]);
  const [isRecording, setIsRecording] = useState<boolean>(false);
  const [recordingSeconds, setRecordingSeconds] = useState<number>(0);
  const [audioLevel, setAudioLevel] = useState<number>(0);
  const [frequencyBands, setFrequencyBands] = useState<number[]>([0, 0, 0, 0, 0, 0, 0, 0]);
  const [isProcessing, setIsProcessing] = useState<boolean>(false);
  const [processingStage, setProcessingStage] = useState<'transcribing' | 'refining' | ''>('');
  const [isAiMode, setIsAiMode] = useState<boolean>(true);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const audioRecorderRef = useRef<AudioRecorder>(new AudioRecorder());
  const timerRef = useRef<any>(null);

  // Sync entries from global synthetic paste events if any
  useEffect(() => {
    const handleSynthetic = (e: any) => {
      const { text, rawTranscript, isAi, filter } = e.detail || {};
      if (text) {
        const newEntry: DictationEntry = {
          id: `entry-${Date.now()}`,
          timestamp: Date.now(),
          mode: isAi ? 'ai' : 'raw',
          rawTranscript: rawTranscript || text,
          resultText: text,
          filter: filter || 'clean_speech',
          provider: config?.LLM_PROVIDER,
          model: config?.LLM_PROVIDER === 'gemini' 
            ? config?.GEMINI_MODEL 
            : config?.LLM_PROVIDER === 'openrouter'
            ? config?.OPENROUTER_MODEL
            : config?.META_MODEL,
        };
        setHistory((prev) => [newEntry, ...prev]);
      }
    };
    window.addEventListener('odicto-synthetic-paste', handleSynthetic);
    return () => window.removeEventListener('odicto-synthetic-paste', handleSynthetic);
  }, [config]);

  // Handle Recording Timer
  useEffect(() => {
    if (isRecording) {
      setRecordingSeconds(0);
      timerRef.current = setInterval(() => {
        setRecordingSeconds((s) => s + 1);
      }, 1000);
    } else {
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
    }
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [isRecording]);

  const handleStartRecording = async () => {
    try {
      sounds.playStartCue();
      haptics.start();
      setIsRecording(true);

      foregroundService.startForeground({
        title: isAiMode ? 'Odicto AI • Studio Dictation' : 'Odicto Voice • Studio Dictation',
        body: 'Listening to speech...',
        isAi: isAiMode,
      });

      await audioRecorderRef.current.start((level, bands) => {
        setAudioLevel(level);
        if (bands) setFrequencyBands(bands);
      });
    } catch (err) {
      console.error('Failed to start studio audio recording:', err);
      setIsRecording(false);
      foregroundService.stopForeground();
      haptics.error();
    }
  };

  const handleStopRecording = async () => {
    if (!isRecording) return;
    setIsRecording(false);
    sounds.playStopCue();
    haptics.stop();
    setAudioLevel(0);
    setFrequencyBands([0, 0, 0, 0, 0, 0, 0, 0]);
    foregroundService.stopForeground();

    setIsProcessing(true);
    setProcessingStage('transcribing');

    try {
      const audioData = await audioRecorderRef.current.stop();
      if (!audioData || !audioData.base64 || audioData.isTooShort) {
        setIsProcessing(false);
        setProcessingStage('');
        return;
      }

      // 1. Transcribe audio with Groq Whisper (adaptive timeout & retry)
      const transcribeRes = await fetchWithTimeoutAndRetry('/api/transcribe', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          audioBase64: audioData.base64,
          mimeType: audioData.mimeType,
        }),
        timeoutMs: 14000,
        retries: 1,
      });

      const transcribeJson = await transcribeRes.json();
      if (!transcribeJson.ok || !transcribeJson.text) {
        throw new Error(transcribeJson.message || transcribeJson.error || 'Failed to transcribe audio.');
      }

      const rawTranscript = transcribeJson.text.trim();
      let finalResult = rawTranscript;

      // 2. Refine with active LLM provider if AI mode is enabled
      if (isAiMode) {
        setProcessingStage('refining');
        try {
          const refineRes = await fetchWithTimeoutAndRetry('/api/refine', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              text: rawTranscript,
              filter: config?.SELECTED_FILTER || 'clean_speech',
            }),
            timeoutMs: 15000,
            retries: 1,
          });

          const refineJson = await refineRes.json();
          if (refineJson.ok && refineJson.result) {
            finalResult = refineJson.result.trim();
          }
        } catch (refineErr) {
          console.warn('DictationStudio AI refinement failed, using raw transcript:', refineErr);
        }
      }

      // 3. Auto-Copy to clipboard
      if (config?.AUTO_COPY_CLIPBOARD ?? true) {
        await copyToClipboard(finalResult);
      }

      sounds.playCompleteCue();

      const newEntry: DictationEntry = {
        id: `studio-${Date.now()}`,
        timestamp: Date.now(),
        mode: isAiMode ? 'ai' : 'raw',
        rawTranscript: rawTranscript,
        resultText: finalResult,
        filter: config?.SELECTED_FILTER || 'clean_speech',
        provider: config?.LLM_PROVIDER,
        model: config?.LLM_PROVIDER === 'gemini' 
          ? config?.GEMINI_MODEL 
          : config?.LLM_PROVIDER === 'openrouter'
          ? config?.OPENROUTER_MODEL
          : config?.META_MODEL,
      };

      setHistory((prev) => [newEntry, ...prev]);
    } catch (err: any) {
      console.error('Studio dictation failure:', err);
      haptics.error();
    } finally {
      setIsProcessing(false);
      setProcessingStage('');
    }
  };

  const handleCopy = async (text: string, id: string) => {
    const success = await copyToClipboard(text);
    if (success) {
      setCopiedId(id);
      setTimeout(() => setCopiedId(null), 2000);
    }
  };

  const handleClearHistory = () => {
    setHistory([]);
  };

  const handleResetContext = async () => {
    await fetch('/api/reset-context', { method: 'POST' });
    sounds.playCompleteCue();
  };

  const activeProvider = config?.LLM_PROVIDER || 'gemini';
  const currentFilter = FILTER_PRESETS.find((f) => f.id === config?.SELECTED_FILTER) || FILTER_PRESETS[0];

  return (
    <div className="space-y-4 pb-28 font-mori" id="dictation-studio-root">
      
      {/* Studio Header Card */}
      <div className="pt-2 px-1 space-y-2">
        <div className="font-mono text-[10px] uppercase tracking-[0.18em] text-[#9ca3af] flex items-center gap-2">
          <span className="w-1.5 h-1.5 rounded-full bg-[#10b981]"></span>
          <span>Voice Studio • Live Dictation</span>
        </div>
        <h2 className="text-3xl text-[#f3f4f6] font-semibold leading-[1.1] tracking-tight">
          System-wide <span className="text-[#6366f1]">Voice Intelligence</span>.
        </h2>
        <p className="text-xs text-[#9ca3af] leading-relaxed">
          Record spoken thoughts with ultra-fast Groq Whisper and refine with Google Gemini or frontier LLMs.
        </p>
      </div>

      {/* Provider & Filter Bar */}
      <div className="bg-[#11131c] border border-[rgba(255,255,255,0.08)] rounded-2xl p-3 shadow-lg flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 rounded-xl bg-[#181b26] border border-[rgba(255,255,255,0.08)] flex items-center justify-center text-[#f3f4f6]">
            <Sparkles className="w-3.5 h-3.5" />
          </div>
          <div>
            <div className="font-mono text-[9px] uppercase tracking-wider text-[#9ca3af]">
              {PROVIDERS[activeProvider]?.name}
            </div>
            <div className="text-xs font-semibold text-[#f3f4f6]">
              {currentFilter.label}
            </div>
          </div>
        </div>

        <div className="flex items-center gap-1.5">
          <button
            onClick={onNavigateToFilters}
            className="px-2.5 py-1.5 rounded-xl bg-[#181b26] hover:bg-[#1f2333] text-[#9ca3af] hover:text-[#f3f4f6] border border-[rgba(255,255,255,0.08)] text-[11px] font-mono uppercase tracking-wider flex items-center gap-1 transition-colors"
          >
            <SlidersHorizontal className="w-3 h-3" />
            <span>Filter</span>
          </button>
          <button
            onClick={onNavigateToSetup}
            className="px-2.5 py-1.5 rounded-xl bg-[#181b26] hover:bg-[#1f2333] text-[#9ca3af] hover:text-[#f3f4f6] border border-[rgba(255,255,255,0.08)] text-[11px] font-mono uppercase tracking-wider flex items-center gap-1 transition-colors"
          >
            <span>Engine</span>
            <ChevronRight className="w-3 h-3" />
          </button>
        </div>
      </div>

      {/* Main Recording Interactive Stage */}
      <div className="bg-[#11131c] border border-[rgba(255,255,255,0.08)] rounded-3xl p-6 shadow-xl flex flex-col items-center justify-center relative overflow-hidden space-y-5">
        
        {/* Top Controls: AI vs Raw & Multi-turn memory */}
        <div className="flex items-center justify-between w-full">
          <button
            type="button"
            id="studio-ai-mode-toggle"
            onClick={() => setIsAiMode(!isAiMode)}
            className={`px-3 py-1.5 rounded-xl font-mono text-[10px] uppercase tracking-wider flex items-center gap-1.5 transition-all ${
              isAiMode 
                ? 'bg-[#f3f4f6] text-[#090a0f] font-semibold shadow-md' 
                : 'bg-[#181b26] border border-[rgba(255,255,255,0.08)] text-[#9ca3af]'
            }`}
          >
            <Sparkles className="w-3 h-3" />
            <span>{isAiMode ? 'AI Refine ON' : 'Raw Speech'}</span>
          </button>

          <button
            type="button"
            id="studio-multi-turn-toggle"
            onClick={() => onToggleMultiTurn(!config?.ENABLE_MULTI_TURN)}
            className={`px-3 py-1.5 rounded-xl font-mono text-[10px] uppercase tracking-wider flex items-center gap-1.5 transition-all ${
              config?.ENABLE_MULTI_TURN
                ? 'bg-[rgba(99,102,241,0.2)] text-[#a5b4fc] border border-[rgba(99,102,241,0.4)]'
                : 'bg-[#181b26] border border-[rgba(255,255,255,0.08)] text-[#9ca3af]'
            }`}
          >
            <BrainCircuit className="w-3 h-3" />
            <span>{config?.ENABLE_MULTI_TURN ? 'Context Memory' : 'Single-Turn'}</span>
          </button>
        </div>

        {/* Live Audio Visualizer Stage */}
        <div className="h-12 flex items-center justify-center w-full px-4">
          {isRecording ? (
            <div className="flex flex-col items-center space-y-2">
              <AudioWaveformVisualizer
                isRecording={isRecording}
                audioLevel={audioLevel}
                frequencyBands={frequencyBands}
                isAi={isAiMode}
              />
              <span className="font-mono text-xs text-[#10b981] font-semibold tracking-widest">
                {Math.floor(recordingSeconds / 60).toString().padStart(2, '0')}:{(recordingSeconds % 60).toString().padStart(2, '0')}
              </span>
            </div>
          ) : isProcessing ? (
            <div className="flex items-center gap-2 text-xs font-mono text-[#f3f4f6]">
              <Loader2 className="w-4 h-4 text-[#6366f1] animate-spin" />
              <span>{processingStage === 'transcribing' ? 'Transcribing Whisper Audio...' : 'Applying AI Formatting...'}</span>
            </div>
          ) : (
            <div className="text-center">
              <span className="font-mono text-[10px] uppercase tracking-[0.14em] text-[#6b7280]">
                Press to begin recording or use floating bar below
              </span>
            </div>
          )}
        </div>

        {/* Giant Central Dictation Button */}
        <div className="relative flex items-center justify-center">
          {isRecording && (
            <div className="absolute w-28 h-28 rounded-full bg-[#10b981]/20 animate-ping" />
          )}
          <button
            type="button"
            id="studio-main-record-btn"
            onClick={isRecording ? handleStopRecording : handleStartRecording}
            disabled={isProcessing}
            className={`w-20 h-20 rounded-full flex items-center justify-center transition-all duration-200 shadow-2xl relative z-10 ${
              isRecording
                ? 'bg-[#10b981] text-[#090a0f] glow-lock-active scale-105'
                : isProcessing
                ? 'bg-[#181b26] text-[#6b7280] opacity-50 cursor-not-allowed'
                : 'bg-[#f3f4f6] hover:bg-white text-[#090a0f] shadow-lg active:scale-95'
            }`}
          >
            {isRecording ? (
              <div className="w-6 h-6 rounded-md bg-[#090a0f]" />
            ) : isProcessing ? (
              <Loader2 className="w-8 h-8 animate-spin text-[#9ca3af]" />
            ) : (
              <Mic className="w-8 h-8" />
            )}
          </button>
        </div>

        <div className="text-center font-mono text-[11px] text-[#9ca3af]">
          {isRecording ? 'Tap to finish and paste' : 'Tap to start recording'}
        </div>
      </div>

      {/* History & Transcripts Section */}
      <div className="space-y-3">
        <div className="flex items-center justify-between px-1">
          <div className="flex items-center gap-2">
            <Clock className="w-3.5 h-3.5 text-[#9ca3af]" />
            <h3 className="font-mono text-xs uppercase tracking-wider text-[#f3f4f6]">
              Voice Stream History ({history.length})
            </h3>
          </div>
          {history.length > 0 && (
            <button
              onClick={handleClearHistory}
              className="text-xs text-[#9ca3af] hover:text-[#ef4444] flex items-center gap-1 font-mono uppercase tracking-wider transition-colors"
            >
              <Trash2 className="w-3 h-3" />
              <span>Clear</span>
            </button>
          )}
        </div>

        {history.length === 0 ? (
          <div className="p-8 rounded-2xl bg-[#11131c] border border-[rgba(255,255,255,0.06)] text-center text-[#6b7280] space-y-2">
            <FileText className="w-8 h-8 mx-auto stroke-1 opacity-50" />
            <p className="text-xs">No dictations recorded yet in this session.</p>
            <p className="text-[10px] font-mono text-[#6b7280]">
              Use the microphone or floating voice bar to transcribe.
            </p>
          </div>
        ) : (
          <div className="space-y-2.5">
            {history.map((entry) => (
              <div
                key={entry.id}
                className="p-4 rounded-2xl bg-[#11131c] border border-[rgba(255,255,255,0.08)] shadow-lg space-y-3"
              >
                <div className="flex items-center justify-between border-b border-[rgba(255,255,255,0.06)] pb-2">
                  <div className="flex items-center gap-1.5">
                    <span className={`font-mono text-[9px] uppercase tracking-wider px-2 py-0.5 rounded-full font-semibold ${
                      entry.mode === 'ai' 
                        ? 'bg-[rgba(99,102,241,0.2)] text-[#a5b4fc] border border-[rgba(99,102,241,0.3)]' 
                        : 'bg-[rgba(255,255,255,0.08)] text-[#9ca3af]'
                    }`}>
                      {entry.mode === 'ai' ? 'AI Refined' : 'Whisper Raw'}
                    </span>
                    {entry.filter && (
                      <span className="font-mono text-[9px] text-[#9ca3af] uppercase">
                        • {entry.filter.replace('_', ' ')}
                      </span>
                    )}
                  </div>

                  <span className="font-mono text-[10px] text-[#6b7280]">
                    {new Date(entry.timestamp).toLocaleTimeString()}
                  </span>
                </div>

                <div className="text-sm text-[#f3f4f6] leading-relaxed whitespace-pre-wrap font-sans">
                  {entry.resultText}
                </div>

                {entry.mode === 'ai' && entry.rawTranscript !== entry.resultText && (
                  <details className="group text-xs text-[#9ca3af] pt-1">
                    <summary className="cursor-pointer font-mono text-[10px] uppercase tracking-wider text-[#6b7280] hover:text-[#9ca3af] select-none">
                      View Raw Whisper Audio Transcript
                    </summary>
                    <p className="mt-1.5 p-2 bg-[#181b26] rounded-xl border border-[rgba(255,255,255,0.06)] font-mono text-[11px] text-[#9ca3af]">
                      {entry.rawTranscript}
                    </p>
                  </details>
                )}

                <div className="flex items-center justify-end pt-1">
                  <button
                    onClick={() => handleCopy(entry.resultText, entry.id)}
                    className="px-2.5 py-1 rounded-lg bg-[#181b26] hover:bg-[#1f2333] text-[#9ca3af] hover:text-[#f3f4f6] text-[11px] font-mono uppercase tracking-wider flex items-center gap-1.5 border border-[rgba(255,255,255,0.08)] transition-colors"
                  >
                    {copiedId === entry.id ? (
                      <>
                        <Check className="w-3 h-3 text-[#10b981]" />
                        <span className="text-[#10b981]">Copied</span>
                      </>
                    ) : (
                      <>
                        <Copy className="w-3 h-3" />
                        <span>Copy</span>
                      </>
                    )}
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

    </div>
  );
};

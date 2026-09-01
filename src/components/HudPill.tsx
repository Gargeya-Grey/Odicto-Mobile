import React from 'react';
import { Mic, Sparkles, Loader2, CheckCircle2, AlertCircle } from 'lucide-react';

export type HudStatus = 'idle' | 'listening' | 'transcribing' | 'refining' | 'done' | 'error';

interface HudPillProps {
  status: HudStatus;
  mode: 'raw' | 'ai';
  message?: string;
}

export const HudPill: React.FC<HudPillProps> = ({ status, mode, message }) => {
  if (status === 'idle') return null;

  return (
    <div
      id="hud-indicator-pill"
      className="fixed top-14 left-1/2 -translate-x-1/2 z-50 flex items-center gap-2.5 px-4 py-2 rounded-full backdrop-blur-2xl shadow-2xl border transition-all duration-200 bg-[#11131c]/98 border-[rgba(255,255,255,0.18)] text-[#f3f4f6] max-w-[92vw] font-mori"
    >
      {status === 'listening' && (
        <>
          <span className="relative flex h-2 w-2">
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-[#10b981] opacity-75"></span>
            <span className="relative inline-flex rounded-full h-2 w-2 bg-[#10b981]"></span>
          </span>
          <div className="flex items-center gap-1.5 font-mono text-xs text-[#10b981]">
            <Mic className="w-3.5 h-3.5 animate-pulse" />
            <span>Listening...</span>
          </div>
          <span className="font-mono text-[9px] uppercase tracking-wider px-2 py-0.5 rounded-full bg-[#181b26] text-[#9ca3af] border border-[rgba(255,255,255,0.08)]">
            {mode === 'ai' ? 'AI Voice' : 'Dictate'}
          </span>
        </>
      )}

      {status === 'transcribing' && (
        <>
          <Loader2 className="w-3.5 h-3.5 text-[#f3f4f6] animate-spin" />
          <span className="font-mono text-xs text-[#f3f4f6]">Transcribing Whisper audio...</span>
        </>
      )}

      {status === 'refining' && (
        <>
          <Sparkles className="w-3.5 h-3.5 text-[#6366f1] animate-spin" />
          <span className="font-mono text-xs text-[#6366f1]">AI Formatting...</span>
        </>
      )}

      {status === 'done' && (
        <>
          <CheckCircle2 className="w-3.5 h-3.5 text-[#10b981]" />
          <span className="font-mono text-xs text-[#f3f4f6]">{message || 'Pasted'}</span>
        </>
      )}

      {status === 'error' && (
        <>
          <AlertCircle className="w-3.5 h-3.5 text-[#ef4444]" />
          <span className="font-mono text-xs text-[#ef4444]">{message || 'Voice Error'}</span>
        </>
      )}
    </div>
  );
};

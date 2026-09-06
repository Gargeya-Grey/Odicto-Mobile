import React from 'react';
import {
  Mic,
  Settings,
  SlidersHorizontal,
  BrainCircuit,
  Sparkles,
  LayoutDashboard,
} from 'lucide-react';
import { AppConfig } from '../types';
import { PROVIDERS } from '../lib/constants';

interface MobileHeaderProps {
  config: AppConfig | null;
  activeTab: 'studio' | 'overlay' | 'filters' | 'setup';
  onOpenSetup: () => void;
  onOpenFilters: () => void;
  onOpenOverlay?: () => void;
}

export const MobileHeader: React.FC<MobileHeaderProps> = ({
  config,
  activeTab,
  onOpenSetup,
  onOpenFilters,
  onOpenOverlay,
}) => {
  const providerInfo = config ? PROVIDERS[config.LLM_PROVIDER] : null;

  return (
    <header className="sticky top-0 z-40 bg-[#0d0e15]/95 backdrop-blur-[24px] border-b border-[rgba(255,255,255,0.08)] px-4 py-3 font-mori">
      <div className="flex items-center justify-between">
        {/* Brand & System Status */}
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-xl bg-[#f3f4f6] flex items-center justify-center text-[#090a0f] shadow-md">
            <Mic className="w-4 h-4" />
          </div>
          <div>
            <div className="flex items-center gap-1.5">
              <h1 className="text-lg font-semibold tracking-tight text-[#f3f4f6]">
                Odicto
              </h1>
              <span className="font-mono text-[9px] uppercase tracking-[0.14em] px-1.5 py-0.5 rounded-[4px] bg-[rgba(255,255,255,0.08)] text-[#9ca3af] border border-[rgba(255,255,255,0.12)] font-semibold">
                Voice OS
              </span>
            </div>
            <div className="flex items-center gap-1.5 font-mono text-[9px] text-[#9ca3af] uppercase tracking-[0.06em]">
              <span className="inline-block w-1.5 h-1.5 rounded-full bg-[#10b981]"></span>
              <span>Whisper STT</span>
              <span className="text-[#4b5563]">•</span>
              <span className="text-[#f3f4f6] font-medium">
                {providerInfo?.name || 'Gemini AI'}
              </span>
            </div>
          </div>
        </div>

        {/* Action Controls */}
        <div className="flex items-center gap-1.5">
          {config?.ENABLE_MULTI_TURN && (
            <span className="font-mono text-[9px] uppercase tracking-wider px-2 py-1 rounded-lg bg-[rgba(99,102,241,0.15)] text-[#a5b4fc] border border-[rgba(99,102,241,0.3)] flex items-center gap-1">
              <BrainCircuit className="w-3 h-3 text-[#a5b4fc]" />
              <span>Memory</span>
            </span>
          )}

          {onOpenOverlay && (
            <button
              id="header-overlay-btn"
              onClick={onOpenOverlay}
              className={`p-2 rounded-xl border text-xs flex items-center gap-1 transition-all duration-200 ${
                activeTab === 'overlay'
                  ? 'bg-[#f3f4f6] text-[#090a0f] border-[#f3f4f6] shadow-sm font-medium'
                  : 'bg-transparent border-transparent text-[#9ca3af] hover:text-[#f3f4f6] hover:border-[rgba(255,255,255,0.1)]'
              }`}
              title="Live Sandbox & Target Apps"
            >
              <LayoutDashboard className="w-4 h-4" />
            </button>
          )}

          <button
            id="header-filters-btn"
            onClick={onOpenFilters}
            className={`p-2 rounded-xl border text-xs flex items-center gap-1 transition-all duration-200 ${
              activeTab === 'filters'
                ? 'bg-[#f3f4f6] text-[#090a0f] border-[#f3f4f6] shadow-sm font-medium'
                : 'bg-transparent border-transparent text-[#9ca3af] hover:text-[#f3f4f6] hover:border-[rgba(255,255,255,0.1)]'
            }`}
            title="Formatting Directives & Presets"
          >
            <SlidersHorizontal className="w-4 h-4" />
          </button>

          <button
            id="header-setup-btn"
            onClick={onOpenSetup}
            className={`p-2 rounded-xl border text-xs flex items-center gap-1 transition-all duration-200 ${
              activeTab === 'setup'
                ? 'bg-[#f3f4f6] text-[#090a0f] border-[#f3f4f6] shadow-sm font-medium'
                : 'bg-transparent border-transparent text-[#9ca3af] hover:text-[#f3f4f6] hover:border-[rgba(255,255,255,0.1)]'
            }`}
            title="Engine & API Key Settings"
          >
            <Settings className="w-4 h-4" />
          </button>
        </div>
      </div>
    </header>
  );
};

import React, { useState, useEffect } from 'react';
import { AppConfig, OutputFilter } from './types';
import { MobileHeader } from './components/MobileHeader';
import { MobileTabBar } from './components/MobileTabBar';
import { DictationStudio } from './components/DictationStudio';
import { SetupForm } from './components/SetupForm';
import { FilterSelector } from './components/FilterSelector';
import { OverlayPlayground } from './components/OverlayPlayground';
import { FloatingVoiceBar } from './components/FloatingVoiceBar';
import { loadSettings, saveSettings } from './lib/settings';
import { Capacitor } from '@capacitor/core';
import { VoiceHome } from './components/VoiceHome';

export function App() {
  return Capacitor.getPlatform() === 'android' ? <VoiceHome /> : <WebApp />;
}

function WebApp() {
  const [activeTab, setActiveTab] = useState<
    'studio' | 'overlay' | 'setup' | 'filters'
  >('overlay');
  const [config, setConfig] = useState<AppConfig>(() => loadSettings());
  const [activeFilter, setActiveFilter] =
    useState<OutputFilter>('clean_speech');

  useEffect(() => {
    setActiveFilter(config.SELECTED_FILTER);
  }, [config.SELECTED_FILTER]);

  const handleSaveConfigPartial = async (updated: Partial<AppConfig>) => {
    setConfig((current) => {
      const next = { ...current, ...updated };
      saveSettings(next);
      return next;
    });
  };

  const handleFilterChange = (filter: OutputFilter) => {
    setActiveFilter(filter);
    handleSaveConfigPartial({ SELECTED_FILTER: filter });
  };

  return (
    <div className="min-h-screen bg-[#090a0f] text-[#f3f4f6] flex flex-col antialiased selection:bg-[#ffffff] selection:text-[#000000] relative overflow-x-hidden font-mori">
      {/* Mobile App Container (Optimized for Mobile Screens & Framed Viewports) */}
      <div className="w-full max-w-md mx-auto min-h-screen flex flex-col bg-[#0d0e15] shadow-2xl relative border-x border-[rgba(255,255,255,0.06)]">
        {/* Mobile Header Bar */}
        <MobileHeader
          config={config}
          activeTab={activeTab}
          onOpenSetup={() => setActiveTab('setup')}
          onOpenFilters={() => setActiveTab('filters')}
          onOpenOverlay={() => setActiveTab('overlay')}
        />

        {/* Dynamic Tab Body */}
        <main className="flex-1 p-4 overflow-y-auto font-mori">
          {activeTab === 'overlay' && (
            <OverlayPlayground config={config} activeFilter={activeFilter} />
          )}

          {activeTab === 'studio' && (
            <DictationStudio
              config={config}
              onNavigateToSetup={() => setActiveTab('setup')}
              onNavigateToFilters={() => setActiveTab('filters')}
              onToggleMultiTurn={(enabled) =>
                handleSaveConfigPartial({ ENABLE_MULTI_TURN: enabled })
              }
              onSwitchProvider={(provider, model) => {
                const update: any = { LLM_PROVIDER: provider };
                if (model) {
                  if (provider === 'gemini') update.GEMINI_MODEL = model;
                  if (provider === 'openrouter')
                    update.OPENROUTER_MODEL = model;
                  if (provider === 'meta') update.META_MODEL = model;
                }
                handleSaveConfigPartial(update);
              }}
            />
          )}

          {activeTab === 'setup' && (
            <SetupForm
              config={config}
              onConfigSaved={() => setConfig(loadSettings())}
            />
          )}

          {activeTab === 'filters' && (
            <FilterSelector
              config={config}
              selectedFilter={activeFilter}
              onSelectFilter={handleFilterChange}
              onSaveConfig={handleSaveConfigPartial}
            />
          )}
        </main>

        {/* Omnipresent Floating Voice Bar */}
        <FloatingVoiceBar
          config={config}
          activeFilter={activeFilter}
          onFilterChange={handleFilterChange}
        />

        {/* Mobile Bottom Tab Bar */}
        <MobileTabBar activeTab={activeTab} onChangeTab={setActiveTab} />
      </div>
    </div>
  );
}

export default App;

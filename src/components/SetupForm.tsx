import React, { useState, useEffect } from 'react';
import { 
  Save, 
  RotateCcw, 
  Check, 
  Eye, 
  EyeOff, 
  Sparkles, 
  Settings, 
  CheckCircle2, 
  AlertCircle,
  RefreshCw,
  Loader2,
  Lock,
  Cpu,
  BrainCircuit,
  Sliders,
  ChevronDown,
  Shield,
  Bell,
  ClipboardCopy,
  ClipboardCheck,
  Copy
} from 'lucide-react';
import { AppConfig, LlmProvider } from '../types';
import { PROVIDERS, GROQ_MODELS } from '../lib/constants';
import { copyToClipboard } from '../lib/clipboard';

interface SetupFormProps {
  config: AppConfig | null;
  onConfigSaved: () => void;
}

export const SetupForm: React.FC<SetupFormProps> = ({ config, onConfigSaved }) => {
  const [formData, setFormData] = useState<AppConfig | null>(null);
  const [showKeys, setShowKeys] = useState<{ [key: string]: boolean }>({});
  const [isSaving, setIsSaving] = useState(false);
  const [saveMessage, setSaveMessage] = useState<{ ok: boolean; message: string } | null>(null);
  const [isTestingProvider, setIsTestingProvider] = useState(false);
  const [isTestingGroq, setIsTestingGroq] = useState(false);
  const [testResult, setTestResult] = useState<{ target: string; ok: boolean; message: string } | null>(null);
  const [testClipboardCopied, setTestClipboardCopied] = useState(false);

  useEffect(() => {
    if (config) {
      setFormData(config);
    }
  }, [config]);

  if (!formData) {
    return (
      <div className="flex flex-col items-center justify-center p-12 text-[#9ca3af] space-y-3 font-mori">
        <Loader2 className="w-6 h-6 animate-spin text-[#f3f4f6]" />
        <span className="font-mono text-xs uppercase tracking-wider">Loading configuration...</span>
      </div>
    );
  }

  const handleChange = (field: keyof AppConfig, value: any) => {
    setFormData((prev) => prev ? { ...prev, [field]: value } : null);
  };

  const toggleShowKey = (keyName: string) => {
    setShowKeys((prev) => ({ ...prev, [keyName]: !prev[keyName] }));
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formData) return;
    setIsSaving(true);
    setSaveMessage(null);

    try {
      const resp = await fetch('/api/save', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(formData),
      });

      const data = await resp.json();
      if (data.ok) {
        setSaveMessage({ ok: true, message: 'Configuration saved successfully.' });
        onConfigSaved();
      } else {
        setSaveMessage({ ok: false, message: data.message || 'Failed to save settings.' });
      }
    } catch (err: any) {
      setSaveMessage({ ok: false, message: err.message || 'Network error while saving.' });
    } finally {
      setIsSaving(false);
      setTimeout(() => setSaveMessage(null), 4000);
    }
  };

  const handleTestProvider = async () => {
    if (!formData) return;
    setIsTestingProvider(true);
    setTestResult(null);

    try {
      const resp = await fetch('/api/test-provider', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          provider: formData.LLM_PROVIDER,
          GEMINI_API_KEY: formData.GEMINI_API_KEY,
          GEMINI_MODEL: formData.GEMINI_MODEL,
          OPENROUTER_API_KEY: formData.OPENROUTER_API_KEY,
          OPENROUTER_MODEL: formData.OPENROUTER_MODEL,
          META_API_KEY: formData.META_API_KEY,
          META_MODEL: formData.META_MODEL,
          META_API_BASE: formData.META_API_BASE,
        }),
      });

      const data = await resp.json();
      setTestResult({ target: 'provider', ok: data.ok, message: data.message });
    } catch (err: any) {
      setTestResult({ target: 'provider', ok: false, message: 'Failed to reach test endpoint.' });
    } finally {
      setIsTestingProvider(false);
    }
  };

  const handleTestGroq = async () => {
    if (!formData) return;
    setIsTestingGroq(true);
    setTestResult(null);

    try {
      const resp = await fetch('/api/test-groq', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          GROQ_API_KEY: formData.GROQ_API_KEY,
          GROQ_MODEL: formData.GROQ_MODEL,
        }),
      });

      const data = await resp.json();
      setTestResult({ target: 'groq', ok: data.ok, message: data.message });
    } catch (err: any) {
      setTestResult({ target: 'groq', ok: false, message: 'Failed to test Groq connection.' });
    } finally {
      setIsTestingGroq(false);
    }
  };

  const handleResetContext = async () => {
    await fetch('/api/reset-context', { method: 'POST' });
    setSaveMessage({ ok: true, message: 'Conversation memory context reset.' });
    setTimeout(() => setSaveMessage(null), 3000);
  };

  const handleResetDefaults = () => {
    if (!formData) return;
    setFormData({
      ...formData,
      LLM_PROVIDER: 'gemini',
      GEMINI_MODEL: 'gemini-3.7-flash',
      GEMINI_THINKING_LEVEL: 'minimal',
      GROQ_MODEL: 'whisper-large-v3-turbo',
      ENABLE_MULTI_TURN: false,
      AUTO_COPY_CLIPBOARD: true,
      SYSTEM_PROMPT: 'You are Odicto, a high-performance system voice intelligence engine. Your task is to polish, refine, or format transcribed voice input accurately according to the active directives, maintaining natural user tone with zero unnecessary meta-commentary.'
    });
  };

  const handleSelectProvider = (p: LlmProvider) => {
    handleChange('LLM_PROVIDER', p);
  };

  const activeProvider = formData.LLM_PROVIDER;

  return (
    <div className="space-y-4 pb-28 font-mori" id="setup-form-container">
      {/* Header */}
      <div className="pt-2 px-1 space-y-2">
        <div className="font-mono text-[10px] uppercase tracking-[0.18em] text-[#9ca3af] flex items-center gap-2">
          <span className="w-1.5 h-1.5 rounded-full bg-[#10b981]"></span>
          <span>Engine Settings & Credentials</span>
        </div>
        <h2 className="text-3xl text-[#f3f4f6] font-semibold leading-[1.1] tracking-tight">
          Configure <span className="text-[#6366f1]">system engines</span> & clipboard sync.
        </h2>
        <p className="text-xs text-[#9ca3af] leading-relaxed">
          Manage speech-to-text models, AI providers, and fail-safe clipboard synchronization.
        </p>
      </div>

      {saveMessage && (
        <div className={`p-3.5 rounded-2xl text-xs flex items-center justify-between border ${
          saveMessage.ok
            ? 'bg-[#11131c] border-[#10b981] text-[#f3f4f6]'
            : 'bg-[#11131c] border-[#ef4444] text-[#f3f4f6]'
        }`}>
          <span>{saveMessage.message}</span>
          <Check className="w-3.5 h-3.5 text-[#10b981]" />
        </div>
      )}

      <form onSubmit={handleSave} className="space-y-4">
        
        {/* 1. AI Backend Provider Switcher */}
        <div className="bg-[#11131c] border border-[rgba(255,255,255,0.08)] rounded-2xl p-4 shadow-lg space-y-3">
          <label className="block font-mono text-[10px] uppercase tracking-[0.14em] text-[#9ca3af]">
            1. Primary Intelligence Engine
          </label>
          
          <div className="grid grid-cols-3 gap-2" id="provider-buttons-grid">
            {(['gemini', 'openrouter', 'meta'] as LlmProvider[]).map((pId) => {
              const meta = PROVIDERS[pId];
              const isSelected = formData.LLM_PROVIDER === pId;
              return (
                <button
                  key={pId}
                  type="button"
                  id={`provider-select-${pId}`}
                  onClick={() => handleSelectProvider(pId)}
                  className={`p-3 rounded-xl border text-left transition-all flex flex-col justify-between ${
                    isSelected
                      ? 'bg-[#f3f4f6] text-[#090a0f] font-semibold border-[#f3f4f6] shadow-md ring-1 ring-white/30'
                      : 'bg-[#181b26] border-[rgba(255,255,255,0.08)] text-[#9ca3af] hover:text-[#f3f4f6]'
                  }`}
                >
                  <div className="flex items-center justify-between w-full">
                    <span className="font-mono text-xs font-semibold">{meta.name.split(' ')[0]}</span>
                    {isSelected && <Check className="w-3.5 h-3.5 stroke-[2.5]" />}
                  </div>
                  <span className="text-[9px] mt-1 opacity-75 truncate">{meta.badge}</span>
                </button>
              );
            })}
          </div>

          <p className="text-xs text-[#9ca3af]">
            {PROVIDERS[formData.LLM_PROVIDER]?.description || PROVIDERS.gemini.description}
          </p>
        </div>

        {/* 2. Provider Credentials & Models */}
        <div className="bg-[#11131c] border border-[rgba(255,255,255,0.08)] rounded-2xl p-4 shadow-lg space-y-3">
          <label className="block font-mono text-[10px] uppercase tracking-[0.14em] text-[#9ca3af] flex items-center justify-between">
            <span>2. {PROVIDERS[formData.LLM_PROVIDER]?.name || 'Google Gemini'} Credentials</span>
            <span className="text-[#10b981]">Active</span>
          </label>

          {/* GEMINI */}
          {formData.LLM_PROVIDER === 'gemini' && (
            <div className="space-y-3">
              <div>
                <label className="block font-mono text-xs text-[#f3f4f6] mb-1 flex items-center justify-between">
                  <span>Gemini API Key</span>
                  <span className="text-[10px] text-[#9ca3af]">Auto-configured via server</span>
                </label>
                <div className="relative">
                  <input
                    type={showKeys['gemini'] ? 'text' : 'password'}
                    id="input-gemini-key"
                    value={formData.GEMINI_API_KEY}
                    onChange={(e) => handleChange('GEMINI_API_KEY', e.target.value)}
                    placeholder="AIzaSy... (or default system key)"
                    className="w-full px-3 py-2 pr-10 text-xs bg-[#0d0e15] border border-[rgba(255,255,255,0.08)] rounded-xl text-[#f3f4f6] font-mono focus:outline-none focus:border-[#f3f4f6]"
                  />
                  <button
                    type="button"
                    onClick={() => toggleShowKey('gemini')}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-[#9ca3af] hover:text-[#f3f4f6]"
                  >
                    {showKeys['gemini'] ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
                  </button>
                </div>
              </div>

              <div>
                <label className="block font-mono text-xs text-[#f3f4f6] mb-1">Gemini Model SKU</label>
                <div className="space-y-1.5">
                  <div className="flex flex-wrap gap-1.5 mb-1.5">
                    {PROVIDERS.gemini.popularModels.map((m) => (
                      <button
                        key={m}
                        type="button"
                        onClick={() => handleChange('GEMINI_MODEL', m)}
                        className={`font-mono text-[10px] px-2 py-0.5 rounded-lg border transition-colors ${
                          formData.GEMINI_MODEL === m
                            ? 'bg-[#f3f4f6] text-[#090a0f] border-[#f3f4f6] font-semibold'
                            : 'bg-[#181b26] text-[#9ca3af] border-[rgba(255,255,255,0.08)] hover:text-[#f3f4f6]'
                        }`}
                      >
                        {m}
                      </button>
                    ))}
                  </div>
                  <input
                    type="text"
                    id="input-gemini-model"
                    value={formData.GEMINI_MODEL}
                    onChange={(e) => handleChange('GEMINI_MODEL', e.target.value)}
                    placeholder="gemini-3.7-flash"
                    className="w-full px-3 py-2 text-xs bg-[#0d0e15] border border-[rgba(255,255,255,0.08)] rounded-xl text-[#f3f4f6] font-mono focus:outline-none focus:border-[#f3f4f6]"
                  />
                </div>
              </div>

              <div>
                <label className="block font-mono text-xs text-[#f3f4f6] mb-1">Thinking Level</label>
                <select
                  value={formData.GEMINI_THINKING_LEVEL}
                  onChange={(e) => handleChange('GEMINI_THINKING_LEVEL', e.target.value)}
                  className="w-full px-3 py-2 text-xs bg-[#0d0e15] border border-[rgba(255,255,255,0.08)] rounded-xl text-[#f3f4f6] focus:outline-none focus:border-[#f3f4f6]"
                >
                  <option value="minimal">minimal (instant low latency streaming)</option>
                  <option value="low">low reasoning</option>
                  <option value="medium">medium reasoning</option>
                  <option value="high">high reasoning</option>
                </select>
              </div>
            </div>
          )}

          {/* OPENROUTER */}
          {activeProvider === 'openrouter' && (
            <div className="space-y-3">
              <div>
                <label className="block font-mono text-xs text-[#f3f4f6] mb-1">OpenRouter API Key</label>
                <div className="relative">
                  <input
                    type={showKeys['openrouter'] ? 'text' : 'password'}
                    id="input-openrouter-key"
                    value={formData.OPENROUTER_API_KEY}
                    onChange={(e) => handleChange('OPENROUTER_API_KEY', e.target.value)}
                    placeholder="sk-or-v1-..."
                    className="w-full px-3 py-2 pr-10 text-xs bg-[#0d0e15] border border-[rgba(255,255,255,0.08)] rounded-xl text-[#f3f4f6] font-mono focus:outline-none focus:border-[#f3f4f6]"
                  />
                  <button
                    type="button"
                    onClick={() => toggleShowKey('openrouter')}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-[#9ca3af] hover:text-[#f3f4f6]"
                  >
                    {showKeys['openrouter'] ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
                  </button>
                </div>
              </div>

              <div>
                <label className="block font-mono text-xs text-[#f3f4f6] mb-1">OpenRouter Model</label>
                <div className="space-y-1.5">
                  <div className="flex flex-wrap gap-1.5 mb-1.5">
                    {PROVIDERS.openrouter.popularModels.map((m) => (
                      <button
                        key={m}
                        type="button"
                        onClick={() => handleChange('OPENROUTER_MODEL', m)}
                        className={`font-mono text-[10px] px-2 py-0.5 rounded-lg border transition-colors ${
                          formData.OPENROUTER_MODEL === m
                            ? 'bg-[#f3f4f6] text-[#090a0f] border-[#f3f4f6] font-semibold'
                            : 'bg-[#181b26] text-[#9ca3af] border-[rgba(255,255,255,0.08)] hover:text-[#f3f4f6]'
                        }`}
                      >
                        {m.split('/')[1] || m}
                      </button>
                    ))}
                  </div>
                  <input
                    type="text"
                    id="input-openrouter-model"
                    value={formData.OPENROUTER_MODEL}
                    onChange={(e) => handleChange('OPENROUTER_MODEL', e.target.value)}
                    placeholder="google/gemini-2.0-flash-001"
                    className="w-full px-3 py-2 text-xs bg-[#0d0e15] border border-[rgba(255,255,255,0.08)] rounded-xl text-[#f3f4f6] font-mono focus:outline-none focus:border-[#f3f4f6]"
                  />
                </div>
              </div>
            </div>
          )}

          {/* META */}
          {activeProvider === 'meta' && (
            <div className="space-y-3">
              <div>
                <label className="block font-mono text-xs text-[#f3f4f6] mb-1">Meta API Key</label>
                <div className="relative">
                  <input
                    type={showKeys['meta'] ? 'text' : 'password'}
                    id="input-meta-key"
                    value={formData.META_API_KEY}
                    onChange={(e) => handleChange('META_API_KEY', e.target.value)}
                    placeholder="sk-meta-..."
                    className="w-full px-3 py-2 pr-10 text-xs bg-[#0d0e15] border border-[rgba(255,255,255,0.08)] rounded-xl text-[#f3f4f6] font-mono focus:outline-none focus:border-[#f3f4f6]"
                  />
                  <button
                    type="button"
                    onClick={() => toggleShowKey('meta')}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-[#9ca3af] hover:text-[#f3f4f6]"
                  >
                    {showKeys['meta'] ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
                  </button>
                </div>
              </div>

              <div>
                <label className="block font-mono text-xs text-[#f3f4f6] mb-1">Meta Model</label>
                <div className="space-y-1.5">
                  <div className="flex flex-wrap gap-1.5 mb-1.5">
                    {PROVIDERS.meta.popularModels.map((m) => (
                      <button
                        key={m}
                        type="button"
                        onClick={() => handleChange('META_MODEL', m)}
                        className={`font-mono text-[10px] px-2 py-0.5 rounded-lg border transition-colors ${
                          formData.META_MODEL === m
                            ? 'bg-[#f3f4f6] text-[#090a0f] border-[#f3f4f6] font-semibold'
                            : 'bg-[#181b26] text-[#9ca3af] border-[rgba(255,255,255,0.08)] hover:text-[#f3f4f6]'
                        }`}
                      >
                        {m}
                      </button>
                    ))}
                  </div>
                  <input
                    type="text"
                    id="input-meta-model"
                    value={formData.META_MODEL}
                    onChange={(e) => handleChange('META_MODEL', e.target.value)}
                    placeholder="muse-spark-1.2-contributor"
                    className="w-full px-3 py-2 text-xs bg-[#0d0e15] border border-[rgba(255,255,255,0.08)] rounded-xl text-[#f3f4f6] font-mono focus:outline-none focus:border-[#f3f4f6]"
                  />
                </div>
              </div>
            </div>
          )}

          {/* Test Provider Button */}
          <div className="pt-1 space-y-2">
            <button
              type="button"
              id="test-provider-btn"
              onClick={handleTestProvider}
              disabled={isTestingProvider}
              className="w-full py-2.5 px-3 rounded-xl bg-[#0d0e15] hover:bg-[#181b26] text-[#f3f4f6] font-mono text-xs uppercase tracking-wider flex items-center justify-center gap-1.5 border border-[rgba(255,255,255,0.12)] transition-colors disabled:opacity-50"
            >
              {isTestingProvider ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5 text-[#6366f1]" />}
              <span>{isTestingProvider ? 'Testing Engine...' : `Test ${PROVIDERS[activeProvider].name}`}</span>
            </button>

            {testResult && testResult.target === 'provider' && (
              <div className={`p-3 rounded-xl text-xs flex items-start gap-2.5 ${
                testResult.ok 
                  ? 'bg-[#0d0e15] border border-[#10b981] text-[#f3f4f6]' 
                  : 'bg-[#0d0e15] border border-[#ef4444] text-[#f3f4f6]'
              }`}>
                {testResult.ok ? (
                  <CheckCircle2 className="w-4 h-4 text-[#10b981] shrink-0 mt-0.5" />
                ) : (
                  <AlertCircle className="w-4 h-4 text-[#ef4444] shrink-0 mt-0.5" />
                )}
                <div className="flex-1">
                  <span className="font-mono text-[10px] uppercase tracking-wider block mb-0.5">
                    {testResult.ok ? 'Connection Verified' : 'Connection Failed'}
                  </span>
                  <span className="text-[#9ca3af] text-xs">{testResult.message}</span>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* 3. Whisper Speech-to-Text Engine */}
        <div className="bg-[#11131c] border border-[rgba(255,255,255,0.08)] rounded-2xl p-4 shadow-lg space-y-3">
          <div className="flex items-center justify-between">
            <label className="block font-mono text-[10px] uppercase tracking-[0.14em] text-[#9ca3af]">
              3. Speech-to-Text Engine (Whisper)
            </label>
            <span className="font-mono text-[9px] uppercase tracking-wider px-2 py-0.5 rounded-full bg-[#10b981]/20 text-[#10b981] font-semibold">
              Cloud STT
            </span>
          </div>

          <div>
            <label className="block font-mono text-xs text-[#f3f4f6] mb-1">Groq API Key</label>
            <div className="relative">
              <input
                type={showKeys['groq'] ? 'text' : 'password'}
                id="input-groq-key"
                value={formData.GROQ_API_KEY}
                onChange={(e) => handleChange('GROQ_API_KEY', e.target.value)}
                placeholder="gsk_..."
                className="w-full px-3 py-2 pr-10 text-xs bg-[#0d0e15] border border-[rgba(255,255,255,0.08)] rounded-xl text-[#f3f4f6] font-mono focus:outline-none focus:border-[#f3f4f6]"
              />
              <button
                type="button"
                onClick={() => toggleShowKey('groq')}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-[#9ca3af] hover:text-[#f3f4f6]"
              >
                {showKeys['groq'] ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
              </button>
            </div>
          </div>

          <div>
            <label className="block font-mono text-xs text-[#f3f4f6] mb-1">Whisper Model SKU</label>
            <select
              value={formData.GROQ_MODEL}
              onChange={(e) => handleChange('GROQ_MODEL', e.target.value)}
              className="w-full px-3 py-2 text-xs bg-[#0d0e15] border border-[rgba(255,255,255,0.08)] rounded-xl text-[#f3f4f6] focus:outline-none focus:border-[#f3f4f6]"
            >
              {GROQ_MODELS.map((gm) => (
                <option key={gm.id} value={gm.id}>{gm.label}</option>
              ))}
            </select>
          </div>

          <div className="space-y-2">
            <button
              type="button"
              id="test-groq-btn"
              onClick={handleTestGroq}
              disabled={isTestingGroq}
              className="w-full py-2.5 px-3 rounded-xl bg-[#0d0e15] hover:bg-[#181b26] text-[#f3f4f6] font-mono text-xs uppercase tracking-wider flex items-center justify-center gap-1.5 border border-[rgba(255,255,255,0.12)] transition-colors disabled:opacity-50"
            >
              {isTestingGroq ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5 text-[#10b981]" />}
              <span>{isTestingGroq ? 'Testing STT...' : 'Test Groq STT Engine'}</span>
            </button>

            {testResult && testResult.target === 'groq' && (
              <div className={`p-3 rounded-xl text-xs flex items-start gap-2.5 ${
                testResult.ok 
                  ? 'bg-[#0d0e15] border border-[#10b981] text-[#f3f4f6]' 
                  : 'bg-[#0d0e15] border border-[#ef4444] text-[#f3f4f6]'
              }`}>
                {testResult.ok ? (
                  <CheckCircle2 className="w-4 h-4 text-[#10b981] shrink-0 mt-0.5" />
                ) : (
                  <AlertCircle className="w-4 h-4 text-[#ef4444] shrink-0 mt-0.5" />
                )}
                <div className="flex-1">
                  <span className="font-mono text-[10px] uppercase tracking-wider block mb-0.5">
                    {testResult.ok ? 'Groq STT Ready' : 'Groq Test Failed'}
                  </span>
                  <span className="text-[#9ca3af] text-xs">{testResult.message}</span>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* 4. Multi-turn Memory */}
        <div className="bg-[#11131c] border border-[rgba(255,255,255,0.08)] rounded-2xl p-4 shadow-lg space-y-3">
          <div className="flex items-center justify-between">
            <div>
              <label htmlFor="multi-turn-switch" className="font-mono text-xs uppercase tracking-wider text-[#f3f4f6] block">
                4. Multi-turn Conversation Memory
              </label>
              <p className="text-xs text-[#9ca3af] mt-0.5">
                Maintains dialogue context across consecutive voice dictations.
              </p>
            </div>
            
            <label className="relative inline-flex items-center cursor-pointer">
              <input
                type="checkbox"
                id="multi-turn-switch"
                checked={formData.ENABLE_MULTI_TURN}
                onChange={(e) => handleChange('ENABLE_MULTI_TURN', e.target.checked)}
                className="sr-only peer"
              />
              <div className="w-10 h-5.5 bg-[#181b26] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-[#f3f4f6] after:border-none after:rounded-full after:h-4.5 after:w-4.5 after:transition-all peer-checked:bg-[#6366f1] peer-checked:after:bg-white"></div>
            </label>
          </div>

          <div className="flex items-center justify-between pt-1 border-t border-[rgba(255,255,255,0.06)]">
            <span className="font-mono text-[10px] text-[#9ca3af]">
              {formData.ENABLE_MULTI_TURN ? 'Context Active' : 'Single Turn (Fresh Memory)'}
            </span>
            <button
              type="button"
              id="clear-chat-memory-btn"
              onClick={handleResetContext}
              className="font-mono text-[10px] uppercase tracking-wider text-[#9ca3af] hover:text-[#f3f4f6] flex items-center gap-1 py-1 px-2.5 rounded-lg bg-[#0d0e15] border border-[rgba(255,255,255,0.08)]"
            >
              <RotateCcw className="w-3 h-3" />
              <span>Reset Memory</span>
            </button>
          </div>
        </div>

        {/* 5. Auto-Copy to System Clipboard */}
        <div className="bg-[#11131c] border border-[rgba(255,255,255,0.08)] rounded-2xl p-4 shadow-lg space-y-3" id="setting-autocopy-section">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2.5">
              <div className="w-8 h-8 rounded-xl bg-[#0d0e15] border border-[rgba(255,255,255,0.08)] flex items-center justify-center text-[#f3f4f6]">
                <ClipboardCopy className="w-4 h-4" />
              </div>
              <div>
                <label htmlFor="auto-copy-switch" className="font-mono text-xs uppercase tracking-wider text-[#f3f4f6] block">
                  5. Auto-Copy to Clipboard
                </label>
                <p className="text-xs text-[#9ca3af] mt-0.5">
                  Guaranteed backup into system clipboard upon recording completion.
                </p>
              </div>
            </div>
            
            <label className="relative inline-flex items-center cursor-pointer">
              <input
                type="checkbox"
                id="auto-copy-switch"
                checked={formData.AUTO_COPY_CLIPBOARD ?? true}
                onChange={(e) => handleChange('AUTO_COPY_CLIPBOARD', e.target.checked)}
                className="sr-only peer"
              />
              <div className="w-10 h-5.5 bg-[#181b26] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-[#f3f4f6] after:border-none after:rounded-full after:h-4.5 after:w-4.5 after:transition-all peer-checked:bg-[#10b981] peer-checked:after:bg-[#090a0f]"></div>
            </label>
          </div>

          <div className="p-3 bg-[#0d0e15] border border-[rgba(255,255,255,0.06)] rounded-xl space-y-2 text-xs">
            <div className="flex items-center justify-between">
              <span className="font-mono text-[10px] text-[#f3f4f6] flex items-center gap-1.5">
                <span className={`w-1.5 h-1.5 rounded-full ${(formData.AUTO_COPY_CLIPBOARD ?? true) ? 'bg-[#10b981]' : 'bg-[#6b7280]'}`}></span>
                <span>{(formData.AUTO_COPY_CLIPBOARD ?? true) ? 'Clipboard Sync Active' : 'Clipboard Sync Disabled'}</span>
              </span>
              <button
                type="button"
                id="test-clipboard-copy-btn"
                onClick={async () => {
                  const sampleText = `[Odicto Voice Backup] System dictation verified at ${new Date().toLocaleTimeString()}`;
                  const success = await copyToClipboard(sampleText);
                  if (success) {
                    setTestClipboardCopied(true);
                    setTimeout(() => setTestClipboardCopied(false), 2500);
                  }
                }}
                className="px-2.5 py-1 font-mono text-[10px] uppercase tracking-wider rounded-lg bg-[#181b26] hover:bg-[#202534] text-[#f3f4f6] border border-[rgba(255,255,255,0.1)] flex items-center gap-1.5 transition-colors"
              >
                {testClipboardCopied ? (
                  <>
                    <ClipboardCheck className="w-3 h-3 text-[#10b981]" />
                    <span>Copied!</span>
                  </>
                ) : (
                  <>
                    <Copy className="w-3 h-3 text-[#9ca3af]" />
                    <span>Test Copy</span>
                  </>
                )}
              </button>
            </div>
            <p className="text-xs text-[#9ca3af] leading-relaxed">
              When enabled, Odicto sends voice output to your cursor and stores a copy in your clipboard so your text is preserved across all apps.
            </p>
          </div>
        </div>

        {/* 6. System Prompt */}
        <details className="group border border-[rgba(255,255,255,0.08)] rounded-2xl p-4 bg-[#11131c] shadow-lg" open>
          <summary className="cursor-pointer font-mono text-xs uppercase tracking-wider text-[#f3f4f6] flex items-center justify-between select-none">
            <span className="flex items-center gap-1.5">
              <Sparkles className="w-3.5 h-3.5 text-[#6366f1]" />
              <span>6. Voice AI System Prompt</span>
            </span>
            <ChevronDown className="w-4 h-4 text-[#9ca3af] group-open:rotate-180 transition-transform" />
          </summary>
          <div className="pt-3 space-y-2">
            <textarea
              id="system-prompt-textarea"
              value={formData.SYSTEM_PROMPT}
              onChange={(e) => handleChange('SYSTEM_PROMPT', e.target.value)}
              rows={5}
              className="w-full p-2.5 text-xs bg-[#0d0e15] border border-[rgba(255,255,255,0.08)] rounded-xl text-[#f3f4f6] font-mono leading-relaxed focus:outline-none focus:border-[#6366f1] resize-none"
            />
          </div>
        </details>

        {/* Save & Reset Actions */}
        <div className="flex items-center gap-2 pt-2">
          <button
            type="submit"
            id="save-all-settings-btn"
            disabled={isSaving}
            className="flex-1 py-3 px-4 rounded-xl bg-[#f3f4f6] hover:bg-white text-[#090a0f] font-mono text-xs uppercase tracking-wider font-semibold flex items-center justify-center gap-2 shadow-lg transition-all disabled:opacity-50"
          >
            {isSaving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
            <span>Save Configuration</span>
          </button>

          <button
            type="button"
            id="reset-all-settings-btn"
            onClick={handleResetDefaults}
            className="py-3 px-3.5 rounded-xl bg-[#181b26] hover:bg-[#202534] text-[#9ca3af] hover:text-[#f3f4f6] text-xs border border-[rgba(255,255,255,0.08)] transition-colors"
            title="Reset Defaults"
          >
            <RotateCcw className="w-4 h-4" />
          </button>
        </div>
      </form>
    </div>
  );
};

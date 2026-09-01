import React from 'react';
import { 
  Sparkles, 
  ListOrdered, 
  Briefcase, 
  Zap, 
  Code, 
  Languages, 
  Mic, 
  Check, 
  SlidersHorizontal,
  Info
} from 'lucide-react';
import { AppConfig, OutputFilter, FilterPreset } from '../types';
import { FILTER_PRESETS } from '../lib/constants';

interface FilterSelectorProps {
  config: AppConfig | null;
  selectedFilter: OutputFilter;
  onSelectFilter: (filter: OutputFilter) => void;
  onSaveConfig: (updated: Partial<AppConfig>) => void;
}

const iconMap: Record<string, React.ReactNode> = {
  Sparkles: <Sparkles className="w-4 h-4" />,
  ListOrdered: <ListOrdered className="w-4 h-4" />,
  Briefcase: <Briefcase className="w-4 h-4" />,
  Zap: <Zap className="w-4 h-4" />,
  Code: <Code className="w-4 h-4" />,
  Languages: <Languages className="w-4 h-4" />,
  Mic: <Mic className="w-4 h-4" />,
};

export const FilterSelector: React.FC<FilterSelectorProps> = ({
  config,
  selectedFilter,
  onSelectFilter,
  onSaveConfig,
}) => {
  return (
    <div className="space-y-4 pb-28 font-mori" id="filter-selector-root">
      {/* Header */}
      <div className="pt-2 px-1 space-y-2">
        <div className="font-mono text-[10px] uppercase tracking-[0.18em] text-[#9ca3af] flex items-center gap-2">
          <span className="w-1.5 h-1.5 rounded-full bg-[#10b981]"></span>
          <span>Formatting Directives & Presets</span>
        </div>
        <h2 className="text-3xl text-[#f3f4f6] font-semibold leading-[1.1] tracking-tight">
          AI formatting <span className="text-[#6366f1]">presets</span>.
        </h2>
        <p className="text-xs text-[#9ca3af] leading-relaxed">
          Select how your spoken voice is styled before it pastes into documents, chat clients, or code editors.
        </p>
      </div>

      {/* Preset Cards List */}
      <div className="space-y-2.5">
        {FILTER_PRESETS.map((preset) => {
          const isSelected = selectedFilter === preset.id;
          return (
            <div
              key={preset.id}
              onClick={() => onSelectFilter(preset.id)}
              className={`p-4 rounded-2xl border transition-all duration-200 cursor-pointer relative ${
                isSelected
                  ? 'bg-[#181b26] border-[#f3f4f6] shadow-xl ring-1 ring-white/20'
                  : 'bg-[#11131c] border-[rgba(255,255,255,0.08)] hover:border-[rgba(255,255,255,0.18)] hover:bg-[#141622]'
              }`}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="flex items-start gap-3">
                  <div className={`p-2.5 rounded-xl transition-colors ${
                    isSelected
                      ? 'bg-[#f3f4f6] text-[#090a0f]'
                      : 'bg-[#181b26] text-[#9ca3af]'
                  }`}>
                    {iconMap[preset.iconName] || <Sparkles className="w-4 h-4" />}
                  </div>

                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-semibold text-[#f3f4f6]">
                        {preset.label}
                      </span>
                      {isSelected && (
                        <span className="font-mono text-[9px] uppercase tracking-wider px-1.5 py-0.5 rounded bg-[#10b981]/20 text-[#10b981] font-bold">
                          Active
                        </span>
                      )}
                    </div>
                    <p className="text-xs text-[#9ca3af] font-light leading-relaxed">
                      {preset.shortDesc}
                    </p>
                  </div>
                </div>

                <div className={`w-5 h-5 rounded-full border flex items-center justify-center transition-colors shrink-0 mt-1 ${
                  isSelected 
                    ? 'bg-[#f3f4f6] border-[#f3f4f6] text-[#090a0f]' 
                    : 'border-[rgba(255,255,255,0.15)] text-transparent'
                }`}>
                  <Check className="w-3 h-3 stroke-[3]" />
                </div>
              </div>

              {/* Prompt Instruction Preview */}
              <div className="mt-3 pt-2.5 border-t border-[rgba(255,255,255,0.06)] font-mono text-[11px] text-[#6b7280] leading-relaxed">
                <span className="text-[#9ca3af] uppercase text-[9px] tracking-wider block mb-0.5">Directive:</span>
                "{preset.promptInstruction}"
              </div>
            </div>
          );
        })}
      </div>

      {/* Info Card */}
      <div className="p-4 rounded-2xl bg-[#11131c] border border-[rgba(255,255,255,0.06)] flex items-start gap-3">
        <Info className="w-4 h-4 text-[#6366f1] shrink-0 mt-0.5" />
        <p className="text-xs text-[#9ca3af] leading-relaxed">
          You can toggle between raw verbatim audio and active AI filter instantly using the <strong>AI</strong> button on the floating bar.
        </p>
      </div>
    </div>
  );
};

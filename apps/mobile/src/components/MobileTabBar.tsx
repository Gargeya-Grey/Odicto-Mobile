import React from 'react';
import {
  Mic,
  Settings,
  SlidersHorizontal,
  LayoutDashboard,
} from 'lucide-react';

interface MobileTabBarProps {
  activeTab: 'studio' | 'overlay' | 'filters' | 'setup';
  onChangeTab: (tab: 'studio' | 'overlay' | 'filters' | 'setup') => void;
}

export const MobileTabBar: React.FC<MobileTabBarProps> = ({
  activeTab,
  onChangeTab,
}) => {
  return (
    <nav className="fixed bottom-0 left-0 right-0 z-40 bg-[#0d0e15]/95 backdrop-blur-[24px] border-t border-[rgba(255,255,255,0.08)] px-3 py-2 flex items-center justify-around max-w-md mx-auto pb-safe font-mori">
      <button
        id="tab-overlay"
        onClick={() => onChangeTab('overlay')}
        className={`flex-1 py-1.5 px-2 flex flex-col items-center gap-1 rounded-xl transition-all duration-200 ${
          activeTab === 'overlay'
            ? 'text-[#f3f4f6] font-medium bg-[rgba(255,255,255,0.08)]'
            : 'text-[#6b7280] hover:text-[#9ca3af]'
        }`}
      >
        <LayoutDashboard className="w-4 h-4" />
        <span className="font-mono text-[9px] uppercase tracking-[0.14em]">
          Sandbox
        </span>
      </button>

      <button
        id="tab-studio"
        onClick={() => onChangeTab('studio')}
        className={`flex-1 py-1.5 px-2 flex flex-col items-center gap-1 rounded-xl transition-all duration-200 ${
          activeTab === 'studio'
            ? 'text-[#f3f4f6] font-medium bg-[rgba(255,255,255,0.08)]'
            : 'text-[#6b7280] hover:text-[#9ca3af]'
        }`}
      >
        <Mic className="w-4 h-4" />
        <span className="font-mono text-[9px] uppercase tracking-[0.14em]">
          Studio
        </span>
      </button>

      <button
        id="tab-filters"
        onClick={() => onChangeTab('filters')}
        className={`flex-1 py-1.5 px-2 flex flex-col items-center gap-1 rounded-xl transition-all duration-200 ${
          activeTab === 'filters'
            ? 'text-[#f3f4f6] font-medium bg-[rgba(255,255,255,0.08)]'
            : 'text-[#6b7280] hover:text-[#9ca3af]'
        }`}
      >
        <SlidersHorizontal className="w-4 h-4" />
        <span className="font-mono text-[9px] uppercase tracking-[0.14em]">
          Filters
        </span>
      </button>

      <button
        id="tab-setup"
        onClick={() => onChangeTab('setup')}
        className={`flex-1 py-1.5 px-2 flex flex-col items-center gap-1 rounded-xl transition-all duration-200 ${
          activeTab === 'setup'
            ? 'text-[#f3f4f6] font-medium bg-[rgba(255,255,255,0.08)]'
            : 'text-[#6b7280] hover:text-[#9ca3af]'
        }`}
      >
        <Settings className="w-4 h-4" />
        <span className="font-mono text-[9px] uppercase tracking-[0.14em]">
          Engine
        </span>
      </button>
    </nav>
  );
};

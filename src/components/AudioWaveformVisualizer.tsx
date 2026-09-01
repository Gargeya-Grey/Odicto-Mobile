import React from 'react';

interface AudioWaveformVisualizerProps {
  isRecording: boolean;
  audioLevel: number; // 0 to 100
  frequencyBands?: number[]; // array of normalized band levels (0-100)
  isAi?: boolean;
  embedded?: boolean; // directly inside the mic button
}

export const AudioWaveformVisualizer: React.FC<AudioWaveformVisualizerProps> = ({
  isRecording,
  audioLevel,
  frequencyBands,
  isAi = false,
  embedded = false,
}) => {
  // Symmetrical wave shape multipliers
  const multipliers = embedded 
    ? [0.35, 0.65, 1.0, 1.3, 1.0, 0.65, 0.35]
    : [0.35, 0.65, 0.95, 1.25, 1.0, 0.7, 0.4];

  if (embedded) {
    return (
      <div 
        id="embedded-audio-waveform"
        className="flex items-center justify-center gap-[3px] h-6 w-full px-1 select-none pointer-events-none"
      >
        {multipliers.map((multiplier, idx) => {
          let barHeight = 4; // baseline height

          if (isRecording) {
            const bandVal = frequencyBands && frequencyBands[idx] !== undefined 
              ? frequencyBands[idx] 
              : audioLevel;
            
            const dynamicFactor = Math.min(100, Math.max(8, (bandVal * 0.7) + (audioLevel * 0.3)));
            barHeight = Math.min(22, Math.max(4, (dynamicFactor / 100) * 18 * multiplier + 3.5));
          }

          return (
            <div
              key={idx}
              className="w-[3px] rounded-full transition-all duration-75 ease-out bg-[#090a0f] shadow-sm"
              style={{
                height: `${barHeight}px`,
              }}
            />
          );
        })}
      </div>
    );
  }

  return (
    <div
      id="realtime-audio-waveform-visualizer"
      className="flex items-center gap-[3px] h-6 px-2.5 py-1 bg-[#0d0e15] rounded-full border border-[rgba(255,255,255,0.08)]"
      title={isRecording ? 'Audio signal active' : 'Microphone idle'}
    >
      {multipliers.map((multiplier, idx) => {
        let barHeight = 4; // Resting baseline height

        if (isRecording) {
          const bandVal = frequencyBands && frequencyBands[idx] !== undefined 
            ? frequencyBands[idx] 
            : audioLevel;
          
          const dynamicFactor = Math.min(100, Math.max(8, (bandVal * 0.7) + (audioLevel * 0.3)));
          barHeight = Math.min(18, Math.max(4, (dynamicFactor / 100) * 16 * multiplier + 3));
        }

        return (
          <div
            key={idx}
            className={`w-[2.5px] rounded-full transition-all duration-75 ease-out ${
              isRecording
                ? isAi
                  ? 'bg-[#6366f1]' // Electric Indigo
                  : 'bg-[#10b981]' // Emerald Green
                : 'bg-[#1f2333]'
            }`}
            style={{
              height: `${barHeight}px`,
            }}
          />
        );
      })}
    </div>
  );
};

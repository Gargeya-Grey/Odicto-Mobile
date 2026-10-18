import { lazy, Suspense } from 'react';
import { Capacitor } from '@capacitor/core';
import { VoiceHome } from './components/VoiceHome';
import { applyTheme } from './lib/theme';

// The browser demo shell is dark only; light mode belongs to the Android screens.
if (Capacitor.getPlatform() !== 'android') applyTheme('dark');

// The web demo shell (and Tailwind) is a separate chunk. On Android it is
// never requested, parsed, or evaluated.
const WebApp = lazy(() => import('./WebApp'));

export function App() {
  return Capacitor.getPlatform() === 'android' ? (
    <VoiceHome />
  ) : (
    <Suspense fallback={null}>
      <WebApp />
    </Suspense>
  );
}

export default App;

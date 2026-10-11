import { useEffect, useState } from 'react';
import { voice, type OpenRouterModel } from '../../native/voice';

/**
 * The OpenRouter model catalog. It loads only while the settings page is open
 * and a key is saved, and it stays cached once loaded.
 */
export function useOpenRouterModels(active: boolean, keySet: boolean) {
  const [models, setModels] = useState<OpenRouterModel[]>([]);
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    if (!keySet) {
      setModels([]);
      setLoaded(false);
      return;
    }
    if (!active || loaded) return;
    let current = true;
    setLoading(true);
    void voice
      .openrouterModels()
      .then(({ models: next }) => {
        if (!current) return;
        setModels(next);
        setLoaded(true);
      })
      .catch(() => {
        if (current) setModels([]);
      })
      .finally(() => {
        if (current) setLoading(false);
      });
    return () => {
      current = false;
    };
  }, [active, keySet, loaded]);

  return { models, loading };
}

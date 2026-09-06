// Resilient Network Lifecycle Helper
// Provides adaptive timeout, automatic retry with exponential backoff & jitter,
// and cancellation management for low-latency voice dictation and AI APIs.

export interface FetchOptions extends RequestInit {
  timeoutMs?: number;
  retries?: number;
  backoffMs?: number;
  onRetry?: (attempt: number, error: any) => void;
}

export async function fetchWithTimeoutAndRetry(
  url: string,
  options: FetchOptions = {},
): Promise<Response> {
  const {
    timeoutMs = 12000,
    retries = 1,
    backoffMs = 300,
    onRetry,
    ...fetchOptions
  } = options;

  let lastError: any = null;

  for (let attempt = 0; attempt <= retries; attempt++) {
    const controller = new AbortController();
    const timer = setTimeout(() => {
      controller.abort(new Error(`Request timed out after ${timeoutMs}ms`));
    }, timeoutMs);

    // If caller provided an external signal, link it
    if (fetchOptions.signal) {
      fetchOptions.signal.addEventListener('abort', () => {
        controller.abort(fetchOptions.signal?.reason);
      });
    }

    try {
      const response = await fetch(url, {
        ...fetchOptions,
        signal: controller.signal,
      });

      clearTimeout(timer);

      // Retry on 502, 503, 504 or 429 if attempts remain
      if (!response.ok && attempt < retries) {
        if ([429, 500, 502, 503, 504].includes(response.status)) {
          const jitter = Math.floor(Math.random() * 150);
          const delay = backoffMs * Math.pow(2, attempt) + jitter;
          if (onRetry) onRetry(attempt + 1, `HTTP ${response.status}`);
          await new Promise((r) => setTimeout(r, delay));
          continue;
        }
      }

      return response;
    } catch (err: any) {
      clearTimeout(timer);
      lastError = err;

      // Check if user explicitly aborted via external signal
      if (fetchOptions.signal?.aborted) {
        throw err;
      }

      if (attempt < retries) {
        const jitter = Math.floor(Math.random() * 150);
        const delay = backoffMs * Math.pow(2, attempt) + jitter;
        if (onRetry) onRetry(attempt + 1, err?.message || 'Network failure');
        await new Promise((r) => setTimeout(r, delay));
      }
    }
  }

  throw (
    lastError ||
    new Error(`Network request to ${url} failed after ${retries + 1} attempts`)
  );
}

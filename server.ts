import express from 'express';
import cors from 'cors';
import path from 'path';
import { GoogleGenAI } from '@google/genai';
import { createServer as createViteServer } from 'vite';

const app = express();
const PORT = 3000;

app.use(cors());
app.use(express.json({ limit: '50mb' }));
app.use(express.urlencoded({ extended: true, limit: '50mb' }));

const DEFAULT_SYSTEM_PROMPT = `You are a precise mobile AI dictation assistant. Your response will be directly inserted or read by the user.

ROLE AND STYLE:
- Act as a diligent assistant. Follow the user's voice instructions exactly.
- When asked to transform text (rewrite, fix grammar, summarize, translate, make professional, make concise, etc.), do precisely that and nothing more.
- If the user asks a question, answer directly and accurately.
- If the user dictates a raw thought, keep it as close to their spoken intention as possible.

FORMAT RULES:
- Output clean, human-readable text.
- Be concise by default. Avoid conversational filler like "Sure, here is..." or "Hope this helps!".
- Write directly with high readability for mobile screen consumption.`;

export interface AppConfigState {
  LLM_PROVIDER: 'gemini' | 'openrouter' | 'meta';
  
  // Groq STT
  GROQ_API_KEY: string;
  GROQ_MODEL: string;

  // Gemini
  GEMINI_API_KEY: string;
  GEMINI_MODEL: string;
  GEMINI_THINKING_LEVEL: string;
  GEMINI_MAX_OUTPUT_TOKENS: number;

  // OpenRouter
  OPENROUTER_API_KEY: string;
  OPENROUTER_MODEL: string;
  OPENROUTER_API_BASE: string;

  // Meta API
  META_API_KEY: string;
  META_MODEL: string;
  META_API_BASE: string;
  META_REASONING_EFFORT: string;
  META_MAX_OUTPUT_TOKENS: number;

  // App settings
  SYSTEM_PROMPT: string;
  ENABLE_MULTI_TURN: boolean;
  SELECTED_FILTER: string;
  PLAY_AUDIO_CUES: boolean;
  SHOW_VISUAL_INDICATOR: boolean;
  AUTO_COPY_CLIPBOARD: boolean;
}

const configStore: AppConfigState = {
  LLM_PROVIDER: 'gemini',
  
  GROQ_API_KEY: process.env.GROQ_API_KEY || '',
  GROQ_MODEL: process.env.GROQ_MODEL || 'whisper-large-v3-turbo',

  GEMINI_API_KEY: process.env.GEMINI_API_KEY || process.env.GOOGLE_API_KEY || '',
  GEMINI_MODEL: 'gemini-3.7-flash',
  GEMINI_THINKING_LEVEL: process.env.GEMINI_THINKING_LEVEL || 'minimal',
  GEMINI_MAX_OUTPUT_TOKENS: Number(process.env.GEMINI_MAX_OUTPUT_TOKENS) || 4096,

  OPENROUTER_API_KEY: process.env.OPENROUTER_API_KEY || '',
  OPENROUTER_MODEL: process.env.OPENROUTER_MODEL || 'google/gemini-2.0-flash-001',
  OPENROUTER_API_BASE: process.env.OPENROUTER_API_BASE || 'https://openrouter.ai/api/v1',

  META_API_KEY: process.env.META_API_KEY || process.env.MODEL_API_KEY || '',
  META_MODEL: process.env.META_MODEL || 'muse-spark-1.2-contributor',
  META_API_BASE: process.env.META_API_BASE || 'https://api.meta.ai/v1',
  META_REASONING_EFFORT: process.env.META_REASONING_EFFORT || 'low',
  META_MAX_OUTPUT_TOKENS: Number(process.env.META_MAX_OUTPUT_TOKENS) || 4096,

  SYSTEM_PROMPT: process.env.SYSTEM_PROMPT || DEFAULT_SYSTEM_PROMPT,
  ENABLE_MULTI_TURN: process.env.ENABLE_MULTI_TURN === 'true',
  SELECTED_FILTER: 'verbatim',
  PLAY_AUDIO_CUES: process.env.PLAY_AUDIO_CUES !== 'false',
  SHOW_VISUAL_INDICATOR: process.env.SHOW_VISUAL_INDICATOR !== 'false',
  AUTO_COPY_CLIPBOARD: process.env.AUTO_COPY_CLIPBOARD !== 'false',
};

// Multi-turn conversation memory
let conversationHistory: Array<{ role: 'user' | 'assistant'; content: string }> = [];

// Health Check
app.get('/api/health', (req, res) => {
  res.json({ 
    status: 'ok', 
    app: 'Odicto Mobile Voice Engine',
    providers: ['gemini', 'openrouter', 'meta'],
    stt: 'groq-whisper'
  });
});

// Config Endpoints
app.get('/api/config', (req, res) => {
  res.json({
    ...configStore,
    GROQ_API_KEY_SET: Boolean(configStore.GROQ_API_KEY || process.env.GROQ_API_KEY),
    GEMINI_API_KEY_SET: Boolean(configStore.GEMINI_API_KEY || process.env.GEMINI_API_KEY),
    OPENROUTER_API_KEY_SET: Boolean(configStore.OPENROUTER_API_KEY || process.env.OPENROUTER_API_KEY),
    META_API_KEY_SET: Boolean(configStore.META_API_KEY || process.env.META_API_KEY),
    DEFAULT_SYSTEM_PROMPT,
  });
});

app.post(['/api/save', '/save'], (req, res) => {
  const body = req.body || {};
  for (const [key, value] of Object.entries(body)) {
    if (key in configStore && value !== undefined && value !== '••••••••••••••••') {
      (configStore as any)[key] = value;
    }
  }
  res.json({ ok: true, message: 'Settings updated successfully!' });
});

app.post(['/api/reset', '/reset'], (req, res) => {
  configStore.LLM_PROVIDER = 'gemini';
  configStore.SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT;
  configStore.GEMINI_MODEL = 'gemini-3.7-flash';
  configStore.GROQ_MODEL = 'whisper-large-v3-turbo';
  configStore.OPENROUTER_MODEL = 'google/gemini-2.0-flash-001';
  configStore.META_MODEL = 'muse-spark-1.2-contributor';
  configStore.SELECTED_FILTER = 'verbatim';
  configStore.ENABLE_MULTI_TURN = false;
  configStore.AUTO_COPY_CLIPBOARD = true;
  conversationHistory = [];
  res.json({ ok: true, message: 'Settings reset to defaults.' });
});

// Reset Context Memory
app.post('/api/reset-context', (req, res) => {
  conversationHistory = [];
  res.json({ ok: true, message: 'Conversation memory cleared.' });
});

// Get Current Chat History
app.get('/api/chat-history', (req, res) => {
  res.json({ history: conversationHistory });
});

// Speech-to-Text Endpoint (Groq Whisper + Gemini Audio Fallback)
app.post('/api/transcribe', async (req, res) => {
  const startTime = Date.now();
  try {
    const { audioBase64, mimeType = 'audio/webm', language = 'en', prompt = '' } = req.body;

    if (!audioBase64) {
      return res.status(400).json({ ok: false, message: 'No audio data provided.' });
    }

    // Convert base64 to buffer
    const buffer = Buffer.from(audioBase64, 'base64');
    if (buffer.length < 450) {
      return res.json({ 
        ok: false, 
        message: 'Audio recording was too brief. Please hold down the mic while speaking.' 
      });
    }

    // Inspect magic bytes from buffer for foolproof container and codec identification
    let filename = 'audio.webm';
    let contentType = 'audio/webm';
    let geminiMimeType = 'audio/webm';

    if (buffer.length >= 4 && buffer[0] === 0x1A && buffer[1] === 0x45 && buffer[2] === 0xDF && buffer[3] === 0xA3) {
      // WebM / Matroska (Opus)
      filename = 'audio.webm';
      contentType = 'audio/webm';
      geminiMimeType = 'audio/webm';
    } else if (buffer.length >= 8 && buffer.toString('ascii', 4, 8) === 'ftyp') {
      // MP4 / M4A / AAC container (iOS Safari & WebKit AAC)
      filename = 'audio.m4a';
      contentType = 'audio/mp4';
      geminiMimeType = 'audio/mp4';
    } else if (buffer.length >= 12 && buffer.toString('ascii', 0, 4) === 'RIFF' && buffer.toString('ascii', 8, 12) === 'WAVE') {
      // RIFF / WAV (Linear PCM)
      filename = 'audio.wav';
      contentType = 'audio/wav';
      geminiMimeType = 'audio/wav';
    } else if (buffer.length >= 4 && buffer.toString('ascii', 0, 4) === 'OggS') {
      // Ogg (Opus/Vorbis)
      filename = 'audio.ogg';
      contentType = 'audio/ogg';
      geminiMimeType = 'audio/ogg';
    } else if (buffer.length >= 4 && buffer.toString('ascii', 0, 4) === 'fLaC') {
      // FLAC Lossless
      filename = 'audio.flac';
      contentType = 'audio/flac';
      geminiMimeType = 'audio/flac';
    } else if (buffer.length >= 3 && (buffer.toString('ascii', 0, 3) === 'ID3' || (buffer[0] === 0xFF && (buffer[1] & 0xE0) === 0xE0))) {
      // MP3
      filename = 'audio.mp3';
      contentType = 'audio/mpeg';
      geminiMimeType = 'audio/mp3';
    } else {
      // Fallback from client mimeType string
      const cleanMime = (mimeType || 'audio/webm').split(';')[0].trim().toLowerCase();
      if (cleanMime.includes('mp4') || cleanMime.includes('m4a') || cleanMime.includes('aac')) {
        filename = 'audio.m4a';
        contentType = 'audio/mp4';
        geminiMimeType = 'audio/mp4';
      } else if (cleanMime.includes('wav')) {
        filename = 'audio.wav';
        contentType = 'audio/wav';
        geminiMimeType = 'audio/wav';
      } else if (cleanMime.includes('ogg')) {
        filename = 'audio.ogg';
        contentType = 'audio/ogg';
        geminiMimeType = 'audio/ogg';
      } else if (cleanMime.includes('mp3') || cleanMime.includes('mpeg')) {
        filename = 'audio.mp3';
        contentType = 'audio/mpeg';
        geminiMimeType = 'audio/mp3';
      } else {
        filename = 'audio.webm';
        contentType = 'audio/webm';
        geminiMimeType = 'audio/webm';
      }
    }

    const groqKey = configStore.GROQ_API_KEY || process.env.GROQ_API_KEY;
    const model = configStore.GROQ_MODEL || 'whisper-large-v3-turbo';
    let groqError: string | null = null;
    let groqAttempts = 0;

    // Strategy 1: Groq Whisper API (State-of-the-Art LPU Ultra-Low Latency Speed with Lifecycle Retry & Timeout)
    if (groqKey) {
      // Calculate dynamic timeout per attempt based on audio payload size (standard ~3s voice chunk needs ~3500ms max)
      const baseTimeoutMs = Math.max(3500, Math.min(7500, 3000 + Math.round((buffer.length / 32000) * 1000)));
      const maxGroqRetries = 2; // Up to 3 attempts total

      for (let attempt = 0; attempt <= maxGroqRetries; attempt++) {
        groqAttempts++;
        const currentTimeout = baseTimeoutMs + attempt * 1200; // e.g. 3500ms, 4700ms, 5900ms

        try {
          // Re-create pristine File and FormData per attempt to prevent stream exhaustion
          const audioFile = new File([buffer], filename, { type: contentType });
          const formData = new FormData();
          formData.append('file', audioFile);
          formData.append('model', model);
          formData.append('response_format', 'verbose_json');
          formData.append('temperature', '0');
          if (language && language !== 'auto') {
            formData.append('language', language);
          }
          if (prompt && prompt.trim()) {
            formData.append('prompt', prompt.trim());
          }

          const groqRes = await fetch('https://api.groq.com/openai/v1/audio/transcriptions', {
            method: 'POST',
            headers: {
              'Authorization': `Bearer ${groqKey}`,
            },
            body: formData,
            signal: AbortSignal.timeout(currentTimeout),
          });

          if (groqRes.ok) {
            const data: any = await groqRes.json();
            const text = data.text?.trim() || '';
            if (text) {
              const latencyMs = Date.now() - startTime;
              return res.json({
                ok: true,
                text,
                model,
                engine: 'groq-whisper',
                duration: data.duration,
                language: data.language,
                latencyMs,
                attempts: groqAttempts,
                retries: attempt,
                container: filename.split('.')[1],
                payloadBytes: buffer.length,
              });
            }
          } else {
            const errText = await groqRes.text();
            if (errText.includes('too short') || errText.includes('Minimum audio length')) {
              return res.json({
                ok: false,
                message: 'Audio recording was too brief. Please hold down the mic while speaking.',
              });
            }

            groqError = `Groq Whisper (${groqRes.status}): ${errText.slice(0, 200)}`;
            console.warn(`Groq attempt ${attempt + 1}/${maxGroqRetries + 1} failed (${groqRes.status}):`, groqError);

            // Retry on transient status codes (429 rate limits, 500/502/503/504 server hiccups)
            if ([429, 500, 502, 503, 504].includes(groqRes.status) && attempt < maxGroqRetries) {
              const jitter = Math.floor(Math.random() * 120);
              const backoff = 200 * Math.pow(2, attempt) + jitter; // 200ms, 400ms + jitter
              await new Promise((resolve) => setTimeout(resolve, backoff));
              continue;
            }

            // If non-transient 4xx error (e.g. 401 Unauthorized), break immediately to fallback
            if (groqRes.status >= 400 && groqRes.status < 500 && groqRes.status !== 429) {
              break;
            }
          }
        } catch (groqFetchErr: any) {
          groqError = groqFetchErr?.name === 'TimeoutError' || groqFetchErr?.message?.includes('timeout')
            ? `Groq Whisper request timed out after ${currentTimeout}ms (Attempt ${attempt + 1})`
            : groqFetchErr?.message || 'Groq connection failed';

          console.warn(`Groq attempt ${attempt + 1}/${maxGroqRetries + 1} exception:`, groqError);

          // Retry on network timeout or connection reset if attempts remain
          if (attempt < maxGroqRetries) {
            const jitter = Math.floor(Math.random() * 100);
            const backoff = 150 * Math.pow(2, attempt) + jitter;
            await new Promise((resolve) => setTimeout(resolve, backoff));
            continue;
          }
        }
      }
    }

    // Strategy 2: Google Gemini Audio STT Fallback (Instant fallback if Groq unavailable or timed out)
    const geminiKey = configStore.GEMINI_API_KEY || process.env.GEMINI_API_KEY || process.env.GOOGLE_API_KEY;
    if (geminiKey) {
      try {
        const ai = new GoogleGenAI({
          apiKey: geminiKey,
          httpOptions: {
            headers: {
              'User-Agent': 'aistudio-build',
            },
          },
        });

        // Determine primary and fallback models for Gemini STT
        let preferredModel = configStore.GEMINI_MODEL || 'gemini-3.7-flash';
        if (preferredModel.includes('2.5') || preferredModel.includes('3.5')) {
          preferredModel = 'gemini-3.7-flash';
        }
        const candidateModels = [preferredModel, 'gemini-3.6-flash', 'gemini-3.7-flash'];
        const uniqueCandidates = Array.from(new Set(candidateModels));

        for (const geminiCandidate of uniqueCandidates) {
          try {
            const geminiRes = await ai.models.generateContent({
              model: geminiCandidate,
              contents: [
                {
                  inlineData: {
                    mimeType: geminiMimeType,
                    data: audioBase64,
                  },
                },
                {
                  text: 'Transcribe this spoken audio accurately and verbatim. Output ONLY the exact transcribed text with standard punctuation and capitalization. Do not include any commentary, greetings, or meta-notes.',
                },
              ],
            });

            const text = geminiRes.text?.trim() || '';
            if (text) {
              const latencyMs = Date.now() - startTime;
              return res.json({
                ok: true,
                text,
                model: geminiCandidate,
                engine: 'gemini-audio',
                latencyMs,
                container: filename.split('.')[1],
                payloadBytes: buffer.length,
              });
            }
          } catch (modelErr: any) {
            console.warn(`Gemini Audio STT with ${geminiCandidate} attempt error:`, modelErr?.message);
            // Continue to next candidate
          }
        }
      } catch (geminiSttErr: any) {
        console.error('Gemini Audio STT fallback failed:', geminiSttErr);
      }
    }

    // If both failed or no keys configured
    if (!groqKey && !geminiKey) {
      return res.json({
        ok: false,
        needsKey: true,
        message: 'No API keys configured for Speech-to-Text. Please add your Groq or Gemini API key in Settings.',
      });
    }

    return res.json({
      ok: false,
      message: groqError || 'Speech transcription could not process the recording. Please speak clearly and try again.',
    });
  } catch (err: any) {
    console.error('Transcribe endpoint error:', err);
    return res.status(500).json({ ok: false, message: err?.message || 'Transcription failed' });
  }
});

// Test Connection for Providers & Groq
app.post(['/api/test', '/api/test-provider', '/api/test-groq', '/test'], async (req, res) => {
  const isGroqTest = req.path.includes('groq') || req.body.testTarget === 'groq';
  const provider = (req.body.provider || req.body.LLM_PROVIDER || configStore.LLM_PROVIDER || 'gemini').toLowerCase();
  const groqKey = req.body.GROQ_API_KEY || configStore.GROQ_API_KEY || process.env.GROQ_API_KEY;

  try {
    if (isGroqTest) {
      if (!groqKey) {
        return res.json({ ok: false, message: 'GROQ_API_KEY is required to test Groq STT.' });
      }
      const model = req.body.GROQ_MODEL || configStore.GROQ_MODEL || 'whisper-large-v3-turbo';
      const checkRes = await fetch('https://api.groq.com/openai/v1/models', {
        headers: { 'Authorization': `Bearer ${groqKey}` }
      });
      if (!checkRes.ok) {
        return res.json({ ok: false, message: `Groq key authentication failed (HTTP ${checkRes.status}).` });
      }
      return res.json({ ok: true, message: `Groq STT connection verified (${model}). Ready for fast dictation.` });
    }

    if (provider === 'gemini') {
      const key = req.body.GEMINI_API_KEY || configStore.GEMINI_API_KEY || process.env.GEMINI_API_KEY;
      if (!key) {
        return res.json({ ok: false, message: 'GEMINI_API_KEY is required to test Google Gemini.' });
      }
      const ai = new GoogleGenAI({
        apiKey: key,
        httpOptions: {
          headers: {
            'User-Agent': 'aistudio-build',
          }
        }
      });
      const modelName = req.body.GEMINI_MODEL || configStore.GEMINI_MODEL || 'gemini-3.7-flash';
      const response = await ai.models.generateContent({
        model: modelName,
        contents: 'ping',
      });
      if (response && response.text) {
        return res.json({ ok: true, message: `Connected to Gemini (${modelName}) successfully.` });
      }
      return res.json({ ok: true, message: `Connected to Gemini (${modelName}).` });
    }

    if (provider === 'openrouter') {
      const key = req.body.OPENROUTER_API_KEY || configStore.OPENROUTER_API_KEY || process.env.OPENROUTER_API_KEY;
      if (!key) {
        return res.json({ ok: false, message: 'OPENROUTER_API_KEY is required to test OpenRouter.' });
      }
      const model = req.body.OPENROUTER_MODEL || configStore.OPENROUTER_MODEL || 'google/gemini-2.0-flash-001';
      const base = req.body.OPENROUTER_API_BASE || configStore.OPENROUTER_API_BASE || 'https://openrouter.ai/api/v1';

      const response = await fetch(`${base.replace(/\/$/, '')}/chat/completions`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${key}`,
          'Content-Type': 'application/json',
          'HTTP-Referer': 'https://odicto.app',
          'X-Title': 'Odicto Mobile',
        },
        body: JSON.stringify({
          model,
          messages: [{ role: 'user', content: 'ping' }],
          max_tokens: 16,
        }),
        signal: AbortSignal.timeout(8000),
      });

      if (!response.ok) {
        const errText = await response.text();
        return res.json({ ok: false, message: `OpenRouter returned HTTP ${response.status}: ${errText.slice(0, 200)}` });
      }
      return res.json({ ok: true, message: `Connected to OpenRouter (${model}) successfully.` });
    }

    if (provider === 'meta') {
      const key = req.body.META_API_KEY || configStore.META_API_KEY || process.env.META_API_KEY || process.env.MODEL_API_KEY;
      if (!key) {
        return res.json({ ok: false, message: 'META_API_KEY (or MODEL_API_KEY) is required to test Meta API.' });
      }
      const model = req.body.META_MODEL || configStore.META_MODEL || 'muse-spark-1.2-contributor';
      const base = (req.body.META_API_BASE || configStore.META_API_BASE || 'https://api.meta.ai/v1').replace(/\/$/, '');
      
      // Strategy 1: Standard OpenAI-compatible format
      try {
        const chatRes = await fetch(`${base}/chat/completions`, {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${key}`,
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            model,
            messages: [{ role: 'user', content: 'ping' }],
            max_tokens: 16,
          }),
          signal: AbortSignal.timeout(8000),
        });

        if (chatRes.ok) {
          return res.json({ ok: true, message: `Connected to Meta API / ${model} (chat completions) successfully.` });
        }
      } catch (errChat) {
        console.warn('Meta chat/completions test attempt failed, trying responses format:', errChat);
      }

      // Strategy 2: Meta Responses API format
      try {
        const respRes = await fetch(`${base}/responses`, {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${key}`,
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            model,
            input: [{ role: 'user', content: [{ type: 'input_text', text: 'ping' }] }],
            max_output_tokens: 16,
          }),
          signal: AbortSignal.timeout(8000),
        });

        if (respRes.ok) {
          return res.json({ ok: true, message: `Connected to Meta API / ${model} (responses endpoint) successfully.` });
        } else {
          const errText = await respRes.text();
          return res.json({ ok: false, message: `Meta API returned HTTP ${respRes.status}: ${errText.slice(0, 200)}` });
        }
      } catch (errResp: any) {
        return res.json({ ok: false, message: `Meta API connection error: ${errResp?.message || 'Host unreachable'}. Verify META_API_BASE (${base}).` });
      }
    }

    return res.json({ ok: false, message: `Unknown provider: ${provider}` });
  } catch (err: any) {
    return res.json({ ok: false, message: err?.message || 'Connection test failed.' });
  }
});

// Helper for Filter Instructions
function getFilterSystemPrompt(basePrompt: string, filter: string): string {
  let filterAddition = '';
  switch (filter) {
    case 'clean_speech':
      filterAddition = '\n\nFILTER INSTRUCTION (CLEAN SPEECH): Clean up the dictated speech. Fix grammar, punctuation, and casing. Remove fillers (um, ah, like, you know, sort of) and speech redundancies while preserving authentic user voice and intent.';
      break;
    case 'bullet_points':
      filterAddition = '\n\nFILTER INSTRUCTION (ACTION BULLETS): Format the input into a clean, concise bulleted list of key takeaways, steps, or action items. Keep each bullet point brief and actionable.';
      break;
    case 'professional':
      filterAddition = '\n\nFILTER INSTRUCTION (PROFESSIONAL TONE): Rephrase the input into polished, polite, and articulate professional language suitable for business messages, emails, or executive memos.';
      break;
    case 'concise':
      filterAddition = '\n\nFILTER INSTRUCTION (ULTRA CONCISE): Distill the core message into the most concise phrasing possible, removing all filler and fluff while preserving essential facts and decisions.';
      break;
    case 'code_markdown':
      filterAddition = '\n\nFILTER INSTRUCTION (TECHNICAL & CODE): Structure technical directives into clean Markdown with code blocks, terminal commands, or technical task lists.';
      break;
    case 'translate_en':
      filterAddition = '\n\nFILTER INSTRUCTION (TRANSLATE TO ENGLISH): Translate any non-English speech into natural, fluent English. If already English, polish the grammar and readability.';
      break;
    case 'verbatim':
    default:
      filterAddition = '';
      break;
  }
  return `${basePrompt}${filterAddition}`;
}

// AI Refinement & Response Generation Endpoint
app.post(['/api/refine', '/api/process-dictation', '/refine'], async (req, res) => {
  const {
    text = '',
    context = '',
    keepHistory = configStore.ENABLE_MULTI_TURN,
    provider = configStore.LLM_PROVIDER,
    model = '',
    filter = configStore.SELECTED_FILTER || 'verbatim',
    systemPrompt = configStore.SYSTEM_PROMPT || DEFAULT_SYSTEM_PROMPT,
  } = req.body;

  if (!text || !text.trim()) {
    return res.json({ ok: true, result: '' });
  }

  const rawClean = text.trim();
  const normalized = rawClean.toLowerCase().replace(/^[.,!?\s]+|[.,!?\s]+$/g, '');

  const resetPhrases = ['reset chat', 'clear chat', 'clear memory', 'reset conversation', 'clear the chat'];
  if (resetPhrases.includes(normalized)) {
    conversationHistory = [];
    return res.json({ ok: true, result: 'Conversation memory cleared. Ready for fresh queries.' });
  }

  const effectivePrompt = getFilterSystemPrompt(systemPrompt, filter);

  try {
    let userMessage = rawClean;
    if (context && context.trim()) {
      userMessage = `Context:\n${context.trim()}\n\nQuery: ${rawClean}`;
    }

    if (keepHistory) {
      conversationHistory.push({ role: 'user', content: userMessage });
      if (conversationHistory.length > 16) {
        conversationHistory = conversationHistory.slice(-16);
      }
    }

    // 1. Google Gemini
    if (provider === 'gemini') {
      const key = configStore.GEMINI_API_KEY || process.env.GEMINI_API_KEY;
      if (!key) {
        return res.json({ 
          ok: true, 
          result: rawClean, 
          note: 'GEMINI_API_KEY not configured. Showing raw transcript.' 
        });
      }

      const ai = new GoogleGenAI({ 
        apiKey: key,
        httpOptions: {
          headers: {
            'User-Agent': 'aistudio-build',
          }
        }
      });
      const modelId = model || configStore.GEMINI_MODEL || 'gemini-3.7-flash';

      let contents: any;
      if (keepHistory && conversationHistory.length > 1) {
        contents = conversationHistory.map(m => ({
          role: m.role === 'assistant' ? 'model' : 'user',
          parts: [{ text: m.content }]
        }));
      } else {
        contents = userMessage;
      }

      let response: any;
      let usedModel = modelId;
      try {
        response = await ai.models.generateContent({
          model: modelId,
          contents,
          config: {
            systemInstruction: effectivePrompt,
            maxOutputTokens: configStore.GEMINI_MAX_OUTPUT_TOKENS || 2048,
          }
        });
      } catch (geminiErr: any) {
        console.warn(`Gemini generation failed on ${modelId}, trying gemini-3.6-flash fallback:`, geminiErr?.message || geminiErr);
        try {
          usedModel = 'gemini-3.6-flash';
          response = await ai.models.generateContent({
            model: usedModel,
            contents,
            config: {
              systemInstruction: effectivePrompt,
              maxOutputTokens: configStore.GEMINI_MAX_OUTPUT_TOKENS || 2048,
            }
          });
        } catch (geminiFallbackErr: any) {
          console.warn(`Gemini fallback failed on gemini-3.6-flash, trying gemini-3.5-flash-lite:`, geminiFallbackErr?.message || geminiFallbackErr);
          usedModel = 'gemini-3.5-flash-lite';
          response = await ai.models.generateContent({
            model: usedModel,
            contents,
            config: {
              systemInstruction: effectivePrompt,
              maxOutputTokens: configStore.GEMINI_MAX_OUTPUT_TOKENS || 2048,
            }
          });
        }
      }

      const reply = response?.text?.trim() || rawClean;
      if (keepHistory) {
        conversationHistory.push({ role: 'assistant', content: reply });
      }
      return res.json({ ok: true, result: reply, provider: 'gemini', model: usedModel, filter });
    }

    // 2. OpenRouter
    if (provider === 'openrouter') {
      const key = configStore.OPENROUTER_API_KEY || process.env.OPENROUTER_API_KEY;
      if (!key) {
        return res.json({ 
          ok: true, 
          result: rawClean, 
          note: 'OPENROUTER_API_KEY not configured. Showing raw transcript.' 
        });
      }
      const modelId = model || configStore.OPENROUTER_MODEL || 'google/gemini-2.0-flash-001';
      const base = configStore.OPENROUTER_API_BASE || 'https://openrouter.ai/api/v1';

      const messages: Array<{ role: string; content: string }> = [{ role: 'system', content: effectivePrompt }];
      const historyList = keepHistory ? conversationHistory : [{ role: 'user', content: userMessage }];
      messages.push(...historyList);

      const response = await fetch(`${base.replace(/\/$/, '')}/chat/completions`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${key}`,
          'Content-Type': 'application/json',
          'HTTP-Referer': 'https://odicto.app',
          'X-Title': 'Odicto Mobile',
        },
        body: JSON.stringify({
          model: modelId,
          messages,
          max_tokens: 2048,
        }),
      });

      if (!response.ok) {
        const errText = await response.text();
        return res.json({ ok: true, result: rawClean, note: `OpenRouter error (${response.status}), raw text displayed.` });
      }

      const data: any = await response.json();
      const reply = data.choices?.[0]?.message?.content?.trim() || rawClean;
      if (keepHistory) {
        conversationHistory.push({ role: 'assistant', content: reply });
      }
      return res.json({ ok: true, result: reply, provider: 'openrouter', model: modelId, filter });
    }

    // 3. Meta API (Supports both Meta AI responses API & OpenAI-compatible endpoints)
    if (provider === 'meta') {
      const key = configStore.META_API_KEY || process.env.META_API_KEY || process.env.MODEL_API_KEY;
      if (!key) {
        return res.json({ 
          ok: true, 
          result: rawClean, 
          note: 'META_API_KEY not configured. Showing raw transcript.' 
        });
      }
      const modelId = model || configStore.META_MODEL || 'muse-spark-1.2-contributor';
      const base = configStore.META_API_BASE || 'https://api.meta.ai/v1';
      const historyList = keepHistory ? conversationHistory : [{ role: 'user', content: userMessage }];

      // Try Meta responses schema first
      try {
        const input: any[] = [{ role: 'system', content: [{ type: 'input_text', text: effectivePrompt }] }];
        for (const m of historyList) {
          input.push({
            role: m.role === 'assistant' ? 'assistant' : 'user',
            content: [{ type: m.role === 'assistant' ? 'output_text' : 'input_text', text: m.content }],
          });
        }

        const response = await fetch(`${base.replace(/\/$/, '')}/responses`, {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${key}`,
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            model: modelId,
            input,
            max_output_tokens: configStore.META_MAX_OUTPUT_TOKENS || 2048,
          }),
          signal: AbortSignal.timeout(12000),
        });

        if (response.ok) {
          const data: any = await response.json();
          const reply = 
            data.output_text?.trim() ||
            data.output?.[0]?.content?.[0]?.text?.trim() ||
            data.output?.[0]?.text?.trim() ||
            data.choices?.[0]?.message?.content?.trim();

          if (reply) {
            if (keepHistory) {
              conversationHistory.push({ role: 'assistant', content: reply });
            }
            return res.json({ ok: true, result: reply, provider: 'meta', model: modelId, filter });
          }
        }
      } catch (e) {
        console.warn('Meta responses API attempt failed, trying chat/completions fallback:', e);
      }

      // Fallback: OpenAI-compatible format if Meta provider is an OpenAI-style endpoint (e.g. v1/chat/completions)
      try {
        const messages: Array<{ role: string; content: string }> = [{ role: 'system', content: effectivePrompt }];
        messages.push(...historyList);

        const altRes = await fetch(`${base.replace(/\/$/, '')}/chat/completions`, {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${key}`,
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            model: modelId,
            messages,
            max_tokens: configStore.META_MAX_OUTPUT_TOKENS || 2048,
          }),
          signal: AbortSignal.timeout(12000),
        });

        if (altRes.ok) {
          const data: any = await altRes.json();
          const reply = data.choices?.[0]?.message?.content?.trim();
          if (reply) {
            if (keepHistory) {
              conversationHistory.push({ role: 'assistant', content: reply });
            }
            return res.json({ ok: true, result: reply, provider: 'meta', model: modelId, filter });
          }
        }
      } catch (e2) {
        console.error('Meta fallback also failed:', e2);
      }

      return res.json({ 
        ok: true, 
        result: rawClean, 
        note: 'Meta API could not process request with current key/model. Showing raw transcript.' 
      });
    }

    return res.json({ ok: true, result: rawClean });
  } catch (err: any) {
    console.error('Refine error:', err);
    return res.json({ ok: true, result: rawClean, error: err?.message });
  }
});

async function startServer() {
  if (process.env.NODE_ENV !== 'production') {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.join(process.cwd(), 'dist');
    app.use(express.static(distPath));
    app.get('*', (req, res) => {
      res.sendFile(path.join(distPath, 'index.html'));
    });
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`Odicto Mobile Server running on http://0.0.0.0:${PORT}`);
  });
}

startServer();

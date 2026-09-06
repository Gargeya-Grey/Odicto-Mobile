import React, { useState, useEffect, useRef } from 'react';
import {
  FileText,
  CheckSquare,
  Terminal,
  Copy,
  Check,
  Trash2,
  Send,
  Sparkles,
  SlidersHorizontal,
  Plus,
  Clock,
  Laptop,
  CheckCircle2,
} from 'lucide-react';
import { AppConfig, OutputFilter } from '../types';
import { copyToClipboard } from '../lib/clipboard';

interface OverlayPlaygroundProps {
  config: AppConfig | null;
  activeFilter?: OutputFilter;
}

export const OverlayPlayground: React.FC<OverlayPlaygroundProps> = ({
  config,
  activeFilter,
}) => {
  const [activeApp, setActiveApp] = useState<
    'notes' | 'tasks' | 'code' | 'email'
  >('notes');
  const [notesContent, setNotesContent] = useState<string>(
    '# Q3 Strategy Memo\n\n- Streamline voice dictation latency across all mobile viewports\n- Enhance hands-free mic locking for continuous transcription\n- Verify clipboard sync with system target applications',
  );

  const [tasks, setTasks] = useState<
    { id: string; text: string; completed: boolean }[]
  >([
    {
      id: '1',
      text: 'Benchmark Groq Whisper large-v3-turbo STT latency',
      completed: true,
    },
    {
      id: '2',
      text: 'Test hold-to-talk thumb slide locking mechanism',
      completed: false,
    },
    {
      id: '3',
      text: 'Verify Mori typography contrast across nocturnal palette',
      completed: false,
    },
  ]);
  const [newTaskInput, setNewTaskInput] = useState<string>('');

  const [codeContent, setCodeContent] = useState<string>(
    '// System Voice Hook Event Listener\nwindow.addEventListener("odicto-synthetic-paste", (e) => {\n  console.log("Transcribed speech delivered:", e.detail.text);\n});',
  );

  const [emailTo, setEmailTo] = useState<string>('team@organization.internal');
  const [emailSubject, setEmailSubject] = useState<string>(
    'Project Status Update',
  );
  const [emailBody, setEmailBody] = useState<string>(
    'Hi team,\n\nThe universal system voice interface is now live with enhanced magnetic locking and low-latency transcription.\n\nBest regards,\nOdicto Voice OS',
  );

  const [lastDeliveredEvent, setLastDeliveredEvent] = useState<{
    text: string;
    time: string;
    filter?: string;
  } | null>(null);
  const [copied, setCopied] = useState<boolean>(false);

  // Listen for synthetic voice paste events from the Floating Voice Bar
  useEffect(() => {
    const handleVoicePaste = (e: any) => {
      const { text, isAi, filter } = e.detail || {};
      if (!text) return;

      const nowStr = new Date().toLocaleTimeString();
      setLastDeliveredEvent({ text, time: nowStr, filter });

      if (activeApp === 'notes') {
        setNotesContent((prev) => (prev ? `${prev}\n\n${text}` : text));
      } else if (activeApp === 'tasks') {
        setTasks((prev) => [
          ...prev,
          {
            id: `task-${Date.now()}`,
            text: text.replace(/^[-*•\d.]\s*/, ''),
            completed: false,
          },
        ]);
      } else if (activeApp === 'code') {
        setCodeContent((prev) => (prev ? `${prev}\n\n${text}` : text));
      } else if (activeApp === 'email') {
        setEmailBody((prev) => (prev ? `${prev}\n\n${text}` : text));
      }
    };

    window.addEventListener('odicto-synthetic-paste', handleVoicePaste);
    return () =>
      window.removeEventListener('odicto-synthetic-paste', handleVoicePaste);
  }, [activeApp]);

  const handleCopyCurrent = async (content: string) => {
    const ok = await copyToClipboard(content);
    if (ok) {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  const handleAddTask = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTaskInput.trim()) return;
    setTasks((prev) => [
      ...prev,
      { id: `task-${Date.now()}`, text: newTaskInput.trim(), completed: false },
    ]);
    setNewTaskInput('');
  };

  const toggleTask = (id: string) => {
    setTasks((prev) =>
      prev.map((t) => (t.id === id ? { ...t, completed: !t.completed } : t)),
    );
  };

  const deleteTask = (id: string) => {
    setTasks((prev) => prev.filter((t) => t.id !== id));
  };

  return (
    <div className="space-y-4 pb-28 font-mori" id="overlay-playground-root">
      {/* Sandbox Header */}
      <div className="pt-2 px-1 space-y-2">
        <div className="font-mono text-[10px] uppercase tracking-[0.18em] text-[#9ca3af] flex items-center gap-2">
          <span className="w-1.5 h-1.5 rounded-full bg-[#10b981]"></span>
          <span>Interactive Target Sandbox</span>
        </div>
        <h2 className="text-3xl text-[#f3f4f6] font-semibold leading-[1.1] tracking-tight">
          Test dictation on{' '}
          <span className="text-[#6366f1]">live applications</span>.
        </h2>
        <p className="text-xs text-[#9ca3af] leading-relaxed">
          Hold the floating voice bar below or slide up to lock hands-free.
          Spoken text streams directly into the active editor and copies to your
          system clipboard.
        </p>
      </div>

      {/* Target Application Switcher Tabs */}
      <div className="grid grid-cols-4 gap-1.5 p-1 bg-[#11131c] rounded-2xl border border-[rgba(255,255,255,0.08)]">
        <button
          onClick={() => setActiveApp('notes')}
          className={`py-2 px-1.5 rounded-xl font-mono text-[10px] uppercase tracking-wider flex flex-col items-center gap-1 transition-all ${
            activeApp === 'notes'
              ? 'bg-[#f3f4f6] text-[#090a0f] font-semibold shadow-md'
              : 'text-[#9ca3af] hover:text-[#f3f4f6]'
          }`}
        >
          <FileText className="w-3.5 h-3.5" />
          <span>Notes</span>
        </button>

        <button
          onClick={() => setActiveApp('tasks')}
          className={`py-2 px-1.5 rounded-xl font-mono text-[10px] uppercase tracking-wider flex flex-col items-center gap-1 transition-all ${
            activeApp === 'tasks'
              ? 'bg-[#f3f4f6] text-[#090a0f] font-semibold shadow-md'
              : 'text-[#9ca3af] hover:text-[#f3f4f6]'
          }`}
        >
          <CheckSquare className="w-3.5 h-3.5" />
          <span>Tasks</span>
        </button>

        <button
          onClick={() => setActiveApp('email')}
          className={`py-2 px-1.5 rounded-xl font-mono text-[10px] uppercase tracking-wider flex flex-col items-center gap-1 transition-all ${
            activeApp === 'email'
              ? 'bg-[#f3f4f6] text-[#090a0f] font-semibold shadow-md'
              : 'text-[#9ca3af] hover:text-[#f3f4f6]'
          }`}
        >
          <Send className="w-3.5 h-3.5" />
          <span>Email</span>
        </button>

        <button
          onClick={() => setActiveApp('code')}
          className={`py-2 px-1.5 rounded-xl font-mono text-[10px] uppercase tracking-wider flex flex-col items-center gap-1 transition-all ${
            activeApp === 'code'
              ? 'bg-[#f3f4f6] text-[#090a0f] font-semibold shadow-md'
              : 'text-[#9ca3af] hover:text-[#f3f4f6]'
          }`}
        >
          <Terminal className="w-3.5 h-3.5" />
          <span>Code</span>
        </button>
      </div>

      {/* Target Application Canvas */}
      <div className="bg-[#11131c] border border-[rgba(255,255,255,0.08)] rounded-3xl p-4 shadow-xl space-y-3 relative">
        {/* App Window Title Bar */}
        <div className="flex items-center justify-between border-b border-[rgba(255,255,255,0.06)] pb-2.5">
          <div className="flex items-center gap-2">
            <div className="flex items-center gap-1">
              <div className="w-2.5 h-2.5 rounded-full bg-[#ef4444]/60"></div>
              <div className="w-2.5 h-2.5 rounded-full bg-[#f59e0b]/60"></div>
              <div className="w-2.5 h-2.5 rounded-full bg-[#10b981]/60"></div>
            </div>
            <span className="font-mono text-[10px] text-[#9ca3af] uppercase tracking-wider ml-1">
              {activeApp === 'notes' && 'Markdown Editor'}
              {activeApp === 'tasks' && 'Action Checklist'}
              {activeApp === 'email' && 'Executive Mail Client'}
              {activeApp === 'code' && 'Terminal Scratchpad'}
            </span>
          </div>

          <div className="flex items-center gap-1.5">
            <button
              onClick={() => {
                if (activeApp === 'notes') handleCopyCurrent(notesContent);
                if (activeApp === 'email')
                  handleCopyCurrent(`${emailSubject}\n\n${emailBody}`);
                if (activeApp === 'code') handleCopyCurrent(codeContent);
                if (activeApp === 'tasks')
                  handleCopyCurrent(
                    tasks
                      .map((t) => `${t.completed ? '[x]' : '[ ]'} ${t.text}`)
                      .join('\n'),
                  );
              }}
              className="p-1.5 rounded-lg bg-[#181b26] hover:bg-[#1f2333] text-[#9ca3af] hover:text-[#f3f4f6] text-xs transition-colors"
              title="Copy current document"
            >
              {copied ? (
                <Check className="w-3.5 h-3.5 text-[#10b981]" />
              ) : (
                <Copy className="w-3.5 h-3.5" />
              )}
            </button>
            <button
              onClick={() => {
                if (activeApp === 'notes') setNotesContent('');
                if (activeApp === 'email') setEmailBody('');
                if (activeApp === 'code') setCodeContent('');
                if (activeApp === 'tasks') setTasks([]);
              }}
              className="p-1.5 rounded-lg bg-[#181b26] hover:bg-[#1f2333] text-[#9ca3af] hover:text-[#ef4444] text-xs transition-colors"
              title="Clear contents"
            >
              <Trash2 className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>

        {/* 1. NOTES / MARKDOWN VIEW */}
        {activeApp === 'notes' && (
          <div className="space-y-2">
            <textarea
              id="sandbox-notes-textarea"
              value={notesContent}
              onChange={(e) => setNotesContent(e.target.value)}
              placeholder="Spoken words stream here in real-time..."
              rows={10}
              className="w-full bg-[#0d0e15] border border-[rgba(255,255,255,0.06)] rounded-2xl p-3.5 text-xs text-[#f3f4f6] leading-relaxed resize-none focus:outline-none focus:border-[#6366f1] font-mono selection:bg-[#6366f1] selection:text-white"
            />
          </div>
        )}

        {/* 2. TASKS / CHECKLIST VIEW */}
        {activeApp === 'tasks' && (
          <div className="space-y-3">
            <form onSubmit={handleAddTask} className="flex gap-2">
              <input
                type="text"
                id="sandbox-task-input"
                value={newTaskInput}
                onChange={(e) => setNewTaskInput(e.target.value)}
                placeholder="Add task or dictate an action..."
                className="flex-1 px-3 py-2 text-xs bg-[#0d0e15] border border-[rgba(255,255,255,0.06)] rounded-xl text-[#f3f4f6] focus:outline-none focus:border-[#6366f1]"
              />
              <button
                type="submit"
                className="px-3 py-2 rounded-xl bg-[#f3f4f6] text-[#090a0f] text-xs font-semibold hover:bg-white transition-colors flex items-center gap-1"
              >
                <Plus className="w-3.5 h-3.5" />
                <span>Add</span>
              </button>
            </form>

            <div className="space-y-1.5 max-h-60 overflow-y-auto pr-1">
              {tasks.map((task) => (
                <div
                  key={task.id}
                  className={`p-2.5 rounded-xl border flex items-center justify-between transition-colors ${
                    task.completed
                      ? 'bg-[#0d0e15]/50 border-[rgba(255,255,255,0.04)] text-[#6b7280]'
                      : 'bg-[#0d0e15] border-[rgba(255,255,255,0.06)] text-[#f3f4f6]'
                  }`}
                >
                  <div
                    onClick={() => toggleTask(task.id)}
                    className="flex items-center gap-2 flex-1 cursor-pointer"
                  >
                    <div
                      className={`w-4 h-4 rounded-md border flex items-center justify-center transition-colors ${
                        task.completed
                          ? 'bg-[#10b981] border-[#10b981] text-[#090a0f]'
                          : 'border-[rgba(255,255,255,0.2)]'
                      }`}
                    >
                      {task.completed && (
                        <Check className="w-3 h-3 stroke-[3]" />
                      )}
                    </div>
                    <span
                      className={`text-xs ${task.completed ? 'line-through' : ''}`}
                    >
                      {task.text}
                    </span>
                  </div>
                  <button
                    onClick={() => deleteTask(task.id)}
                    className="text-[#6b7280] hover:text-[#ef4444] p-1"
                  >
                    <Trash2 className="w-3 h-3" />
                  </button>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* 3. EMAIL CLIENT VIEW */}
        {activeApp === 'email' && (
          <div className="space-y-2.5">
            <div className="space-y-1.5">
              <input
                type="text"
                value={emailTo}
                onChange={(e) => setEmailTo(e.target.value)}
                placeholder="Recipient"
                className="w-full px-3 py-1.5 text-xs bg-[#0d0e15] border border-[rgba(255,255,255,0.06)] rounded-xl text-[#f3f4f6] font-mono focus:outline-none focus:border-[#6366f1]"
              />
              <input
                type="text"
                value={emailSubject}
                onChange={(e) => setEmailSubject(e.target.value)}
                placeholder="Subject line"
                className="w-full px-3 py-1.5 text-xs bg-[#0d0e15] border border-[rgba(255,255,255,0.06)] rounded-xl text-[#f3f4f6] font-medium focus:outline-none focus:border-[#6366f1]"
              />
            </div>
            <textarea
              id="sandbox-email-textarea"
              value={emailBody}
              onChange={(e) => setEmailBody(e.target.value)}
              placeholder="Dictate your email here..."
              rows={7}
              className="w-full bg-[#0d0e15] border border-[rgba(255,255,255,0.06)] rounded-2xl p-3.5 text-xs text-[#f3f4f6] leading-relaxed resize-none focus:outline-none focus:border-[#6366f1]"
            />
          </div>
        )}

        {/* 4. CODE / TERMINAL VIEW */}
        {activeApp === 'code' && (
          <div className="space-y-2">
            <textarea
              id="sandbox-code-textarea"
              value={codeContent}
              onChange={(e) => setCodeContent(e.target.value)}
              placeholder="// Spoken technical prompts format into code..."
              rows={10}
              className="w-full bg-[#090a0f] border border-[rgba(255,255,255,0.08)] rounded-2xl p-3.5 text-xs text-[#10b981] font-mono leading-relaxed resize-none focus:outline-none focus:border-[#10b981]"
            />
          </div>
        )}

        {/* Status Bar */}
        <div className="pt-2 border-t border-[rgba(255,255,255,0.06)] flex items-center justify-between text-[10px] font-mono text-[#9ca3af]">
          <div className="flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-[#10b981]"></span>
            <span>Target Ready for Voice Paste</span>
          </div>
          {lastDeliveredEvent && (
            <span className="text-[#6366f1] truncate max-w-[150px]">
              Last: {lastDeliveredEvent.time}
            </span>
          )}
        </div>
      </div>

      {/* Guide Cards */}
      <div className="grid grid-cols-2 gap-2.5">
        <div className="p-3.5 rounded-2xl bg-[#11131c] border border-[rgba(255,255,255,0.06)] space-y-1.5">
          <div className="flex items-center gap-1.5 text-xs font-semibold text-[#f3f4f6]">
            <span className="w-1.5 h-1.5 rounded-full bg-[#10b981]"></span>
            <span>Magnetic Lock</span>
          </div>
          <p className="text-[11px] text-[#9ca3af] leading-relaxed">
            Slide thumb up 34px while holding to lock hands-free, or tap the
            dedicated <strong>Lock icon</strong>.
          </p>
        </div>

        <div className="p-3.5 rounded-2xl bg-[#11131c] border border-[rgba(255,255,255,0.06)] space-y-1.5">
          <div className="flex items-center gap-1.5 text-xs font-semibold text-[#f3f4f6]">
            <span className="w-1.5 h-1.5 rounded-full bg-[#6366f1]"></span>
            <span>Auto Clipboard</span>
          </div>
          <p className="text-[11px] text-[#9ca3af] leading-relaxed">
            Audio output automatically copies to your clipboard as a reliable
            fail-safe backup.
          </p>
        </div>
      </div>
    </div>
  );
};

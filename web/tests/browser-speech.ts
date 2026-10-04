// Browser speech platform doubles shared by speech and voice-session tests.

import { vi } from "./expect";
import type { BrowserRecognition, RecognitionResultEvent } from "../src/BrowserSpeech";

export class FakeRecognition implements BrowserRecognition {
  static instances: FakeRecognition[] = [];
  static signalsStart = true;
  continuous = false;
  interimResults = false;
  lang = "";
  maxAlternatives = 0;
  unspokenPunctuation = false;
  onstart: (() => void) | null = null;
  onend: (() => void) | null = null;
  onerror: ((event: { error: string }) => void) | null = null;
  onresult: ((event: RecognitionResultEvent) => void) | null = null;
  aborted = false;

  constructor() {
    FakeRecognition.instances.push(this);
  }
  start(): void {
    if (FakeRecognition.signalsStart) this.onstart?.();
  }
  abort(): void {
    this.aborted = true;
  }
  end(): void {
    this.onend?.();
  }
  result(entries: Array<[string, boolean]>): void {
    this.onresult?.({
      resultIndex: 0,
      results: entries.map(([transcript, isFinal]) => ({ isFinal, 0: { transcript } })),
    });
  }
}

class FakeUtterance extends EventTarget {
  lang = "";
  voice: SpeechSynthesisVoice | null = null;
  onend: ((event: SpeechSynthesisEvent) => void) | null = null;
  onerror: ((event: SpeechSynthesisErrorEvent) => void) | null = null;
  constructor(public text: string) {
    super();
  }
}

export const fakeSynthesis = {
  spoken: [] as SpeechSynthesisUtterance[],
  getVoices: (): SpeechSynthesisVoice[] => [],
  speak: vi.fn((utterance: SpeechSynthesisUtterance) => {
    fakeSynthesis.spoken.push(utterance);
  }),
  cancel: vi.fn(() => {}),
  resume: vi.fn(() => {}),
  complete(): void {
    const utterance = fakeSynthesis.spoken.at(-1);
    utterance?.onend?.call(utterance, new Event("end") as SpeechSynthesisEvent);
  },
};

export function installBrowserSpeech(userAgent: string): () => void {
  const descriptors = [
    [window, "SpeechRecognition"],
    [window, "webkitSpeechRecognition"],
    [window, "speechSynthesis"],
    [navigator, "userAgent"],
  ] as const;
  const previous = descriptors.map(([host, key]) => Object.getOwnPropertyDescriptor(host, key));
  Object.defineProperty(window, "SpeechRecognition", { configurable: true, value: FakeRecognition });
  Object.defineProperty(window, "speechSynthesis", { configurable: true, value: fakeSynthesis });
  Object.defineProperty(navigator, "userAgent", { configurable: true, value: userAgent });
  vi.stubGlobal("SpeechSynthesisUtterance", FakeUtterance);
  FakeRecognition.instances = [];
  FakeRecognition.signalsStart = true;
  fakeSynthesis.spoken = [];
  return () => {
    descriptors.forEach(([host, key], index) => {
      const descriptor = previous[index];
      if (descriptor) Object.defineProperty(host, key, descriptor);
      else Reflect.deleteProperty(host, key);
    });
    vi.unstubAllGlobals();
  };
}

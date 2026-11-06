// Browser recognition and synthesis with platform-specific utterance and restart handling.

export interface RecognitionResultEvent {
  resultIndex: number;
  results: ArrayLike<{ isFinal: boolean; 0: { transcript: string } }>;
}

export interface BrowserRecognition {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  maxAlternatives: number;
  unspokenPunctuation?: boolean;
  onstart: (() => void) | null;
  onend: (() => void) | null;
  onerror: ((event: { error: string }) => void) | null;
  onresult: ((event: RecognitionResultEvent) => void) | null;
  start(): void;
  abort(): void;
}

type RecognitionConstructor = new () => BrowserRecognition;

function recognitionConstructor(): RecognitionConstructor | null {
  // Ordinary Firefox installations do not enable recognition. Constructor
  // presence in other browsers does not promise an installed language model.
  if (/Firefox\//i.test(navigator.userAgent)) return null;
  const host = window as Window & {
    SpeechRecognition?: RecognitionConstructor;
    webkitSpeechRecognition?: RecognitionConstructor;
  };
  return host.SpeechRecognition ?? host.webkitSpeechRecognition ?? null;
}

export function browserSpeechAvailable(): boolean {
  return recognitionConstructor() !== null && "speechSynthesis" in window;
}

/** iOS recognition can stall after UI audio playback; omit voice-mode chimes. */
export function browserSpeechAvoidsChimes(): boolean {
  return (
    /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1)
  );
}

interface SpeechCallbacks {
  listening(value: boolean): void;
  speaking(value: boolean): void;
  interim(text: string): void;
  final(text: string): void;
  error(message: string, permissionDenied?: boolean): void;
}

const MAX_SPEECH_FRAGMENTS = 64;
const MAX_SPEECH_CHARS = 16384;

export class BrowserSpeech {
  private recognition: BrowserRecognition | null = null;
  private accepting = false;
  private wantsListening = false;
  private disposed = false;
  private restartTimer: ReturnType<typeof setTimeout> | null = null;
  private startupTimer: ReturnType<typeof setTimeout> | null = null;
  private utterance: SpeechSynthesisUtterance | null = null;
  private voicesCleanup: (() => void) | null = null;
  // Includes active speech, not just pending fragments. Fail visibly rather
  // than let a fast generation build an unbounded synthesis backlog.
  private speechQueue: string[] = [];
  private speechChars = 0;
  private boundaryTimer: ReturnType<typeof setTimeout> | null = null;
  private activityTimer: ReturnType<typeof setTimeout> | null = null;
  private abortTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private readonly languageTag: string,
    private readonly voiceURI: string,
    private readonly rate: number,
    private readonly callbacks: SpeechCallbacks,
  ) {}

  listen(): void {
    if (this.disposed) return;
    this.wantsListening = true;
    if (this.recognition === null && this.restartTimer === null) this.startRecognition();
  }

  stopListening(): void {
    const recognition = this.accepting ? this.recognition : null;
    this.wantsListening = false;
    this.accepting = false;
    this.clearTimers();
    this.callbacks.listening(false);
    if (recognition !== null) {
      this.abortTimer = setTimeout(() => {
        this.abortTimer = null;
        if (!this.disposed && this.recognition === recognition) {
          this.wantsListening = false;
          this.callbacks.error("Browser speech did not stop. Retry or use Cloud voice.");
        }
      }, 5000);
      recognition.abort();
    }
  }

  speak(text: string): void {
    if (this.disposed || text === "") return;
    if (
      this.speechQueue.length + (this.utterance === null ? 0 : 1) >= MAX_SPEECH_FRAGMENTS ||
      this.speechChars + text.length > MAX_SPEECH_CHARS
    )
      throw new Error("Browser speech queue is full. Retry with a shorter reply.");
    this.speechQueue.push(text);
    this.speechChars += text.length;
    if (this.utterance === null && this.voicesCleanup === null) this.startSpeaking();
  }

  stopSpeaking(): void {
    this.voicesCleanup?.();
    this.voicesCleanup = null;
    const active = this.utterance !== null;
    // Revoke playback identity before cancel(), which may dispatch callbacks.
    this.utterance = null;
    this.speechQueue = [];
    this.speechChars = 0;
    if (active) window.speechSynthesis.cancel();
  }

  private startSpeaking(): void {
    const text = this.speechQueue[0];
    if (text === undefined) {
      this.callbacks.speaking(false);
      return;
    }
    this.stopListening();
    try {
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.lang = this.languageTag;
      utterance.rate = this.rate;
      const voices = window.speechSynthesis.getVoices();
      if (this.voiceURI !== "" && voices.length === 0) {
        // Chrome loads voices asynchronously on a fresh page. Keep the first
        // fragment queued until discovery completes, with bounded recovery.
        if (this.voicesCleanup !== null) return;
        const ready = () => {
          if (window.speechSynthesis.getVoices().length === 0) return;
          this.voicesCleanup?.();
          this.voicesCleanup = null;
          this.startSpeaking();
        };
        const timer = setTimeout(() => {
          this.stopSpeaking();
          this.callbacks.error("Browser voices did not load. Retry or choose another speaking voice in settings.");
        }, 5000);
        window.speechSynthesis.addEventListener("voiceschanged", ready);
        this.voicesCleanup = () => {
          clearTimeout(timer);
          window.speechSynthesis.removeEventListener("voiceschanged", ready);
        };
        this.callbacks.speaking(true);
        return;
      }
      const language = this.languageTag.toLowerCase().split("-")[0];
      // macOS includes novelty voices. Enumeration order and localService do
      // not indicate quality; honor the user's selection or browser default.
      const voice =
        this.voiceURI === ""
          ? voices.find((candidate) => candidate.default && candidate.lang.toLowerCase().split("-")[0] === language)
          : voices.find((candidate) => candidate.voiceURI === this.voiceURI);
      if (this.voiceURI !== "" && !voice)
        throw new Error("The saved browser voice is unavailable. Choose another speaking voice in settings.");
      if (voice) utterance.voice = voice;
      this.speechQueue.shift();
      this.utterance = utterance;
      utterance.onend = () => {
        if (this.utterance !== utterance || this.disposed) return;
        this.utterance = null;
        this.speechChars -= text.length;
        this.startSpeaking();
      };
      utterance.onerror = (event) => {
        if (this.utterance !== utterance || this.disposed) return;
        this.stopSpeaking();
        this.callbacks.error(`Browser speech synthesis failed: ${event.error}`);
      };
      this.callbacks.speaking(true);
      window.speechSynthesis.speak(utterance);
    } catch (error) {
      this.stopSpeaking();
      this.callbacks.error(`Browser speech synthesis failed: ${String(error)}`);
    }
  }

  close(): void {
    this.disposed = true;
    this.stopListening();
    if (this.abortTimer !== null) clearTimeout(this.abortTimer);
    this.abortTimer = null;
    if (this.recognition !== null) {
      this.recognition.onstart = null;
      this.recognition.onend = null;
      this.recognition.onerror = null;
      this.recognition.onresult = null;
      this.recognition = null;
    }
    this.stopSpeaking();
  }

  private startRecognition(): void {
    const Constructor = recognitionConstructor();
    if (Constructor === null) {
      this.callbacks.error("Browser speech recognition is unavailable. Use Cloud voice.");
      return;
    }
    const recognition = new Constructor();
    this.recognition = recognition;
    this.accepting = true;
    // textarea/DICTATION.md at 97dd5ec: Android's continuous mode exposes
    // overlapping partial results as finals. Use one-shot sessions there.
    recognition.continuous = !/Android/i.test(navigator.userAgent);
    recognition.interimResults = true;
    recognition.lang = this.languageTag;
    recognition.maxAlternatives = 1;
    if ("unspokenPunctuation" in recognition) recognition.unspokenPunctuation = true;
    let delivered = false;
    let final = "";
    let interim = "";
    const deliver = () => {
      if (delivered || !this.accepting || !this.wantsListening || final.trim() === "" || interim.trim() !== "") return;
      delivered = true;
      this.stopListening();
      this.callbacks.final(final.trim());
    };
    const watchActivity = () => {
      if (this.activityTimer !== null) clearTimeout(this.activityTimer);
      this.activityTimer = setTimeout(() => {
        if (final.trim() !== "") {
          interim = "";
          deliver();
          return;
        }
        // Silence is not a failure. Cycle the engine to recover silent stalls,
        // but wait for end before restarting. The abort watchdog catches hangs.
        this.stopListening();
        this.wantsListening = true;
      }, 30000);
    };
    this.callbacks.interim("");
    recognition.onstart = () => {
      if (this.recognition !== recognition || !this.accepting || !this.wantsListening) return;
      if (this.startupTimer !== null) clearTimeout(this.startupTimer);
      this.startupTimer = null;
      this.callbacks.listening(true);
      watchActivity();
    };
    recognition.onresult = (event) => {
      if (this.recognition !== recognition || !this.accepting || !this.wantsListening || delivered) return;
      // Results are a complete session snapshot, not new text. Replace the
      // interim suffix; never append overlapping Android partial hypotheses.
      watchActivity();
      if (this.boundaryTimer !== null) clearTimeout(this.boundaryTimer);
      this.boundaryTimer = null;
      final = "";
      interim = "";
      for (let index = 0; index < event.results.length; index++) {
        const result = event.results[index];
        if (!result) continue;
        const text = result[0].transcript;
        if (result.isFinal) final += text;
        else interim += text;
      }
      this.callbacks.interim((final + interim).trim());
      if (!recognition.continuous) deliver();
      else if (final.trim() !== "" && interim.trim() === "") {
        // Continuous engines can finalize a segment before the utterance ends.
        // Wait for a quiet boundary; later snapshots replace the whole draft.
        this.boundaryTimer = setTimeout(deliver, 800);
      }
    };
    recognition.onerror = (event) => {
      if (this.recognition !== recognition || !this.accepting || !this.wantsListening) return;
      if (event.error === "no-speech") return;
      this.stopListening();
      this.callbacks.error(`Browser speech recognition failed: ${event.error}`, event.error === "not-allowed");
    };
    recognition.onend = () => {
      if (this.recognition !== recognition) return;
      // End is a hard boundary. Preserve committed speech even when the
      // engine abandons its last unfinished hypothesis.
      interim = "";
      deliver();
      this.recognition = null;
      this.accepting = false;
      this.clearTimers();
      if (this.abortTimer !== null) clearTimeout(this.abortTimer);
      this.abortTimer = null;
      this.callbacks.listening(false);
      // Never start a replacement before end. A mute, turn, or disconnect
      // clears wantsListening and suppresses this restart.
      if (this.wantsListening && !this.disposed) {
        this.restartTimer = setTimeout(() => {
          this.restartTimer = null;
          if (this.wantsListening && !this.disposed) this.startRecognition();
        }, 250);
      }
    };
    this.startupTimer = setTimeout(() => {
      if (this.recognition !== recognition || !this.wantsListening) return;
      this.stopListening();
      this.callbacks.error("Browser speech did not start. Retry or use Cloud voice.");
    }, 5000);
    try {
      recognition.start();
    } catch (error) {
      this.recognition = null;
      this.stopListening();
      this.callbacks.error(error instanceof Error ? error.message : "Could not start browser speech");
    }
  }

  private clearTimers(): void {
    if (this.restartTimer !== null) clearTimeout(this.restartTimer);
    if (this.startupTimer !== null) clearTimeout(this.startupTimer);
    this.restartTimer = null;
    this.startupTimer = null;
    if (this.boundaryTimer !== null) clearTimeout(this.boundaryTimer);
    if (this.activityTimer !== null) clearTimeout(this.activityTimer);
    this.boundaryTimer = null;
    this.activityTimer = null;
  }
}

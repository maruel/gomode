// Plays the shared piano score with bounded live synthesis during half-duplex thinking turns.

import score from "../../assets/thinking-score.json";

export interface ThinkingEarcon {
  prepare(): void;
  setThinking(active: boolean): void;
}

interface PianoVoice {
  sources: OscillatorNode[];
  nodes: AudioNode[];
}

class ThinkingPiano {
  private readonly nodes: AudioNode[] = [];
  private readonly voices = new Set<PianoVoice>();
  private readonly output: GainNode;
  private readonly input: BiquadFilterNode;
  private readonly started: number;
  private timer: ReturnType<typeof setInterval> | null = null;
  private note = 0;
  private cycle = 0;

  constructor(
    private readonly context: AudioContext,
    private readonly loop: boolean,
    private readonly onStopped: (error: unknown) => void,
  ) {
    this.started = context.currentTime + 0.2;
    this.output = this.own(context.createGain());
    this.input = this.own(context.createBiquadFilter());
  }

  private connect(): void {
    const context = this.context;
    this.output.gain.setValueAtTime(0, context.currentTime);
    this.output.gain.setValueAtTime(0, this.started);
    this.output.gain.linearRampToValueAtTime(1, this.started + 1.2);
    this.output.connect(context.destination);
    this.input.type = "lowpass";
    this.input.frequency.value = 4200;
    this.input.Q.value = 0.4;
    this.input.connect(this.output);
    for (const echo of score.echoes) {
      const delay = this.own(context.createDelay(1));
      delay.delayTime.value = echo.delay;
      const warmth = this.own(context.createBiquadFilter());
      warmth.type = "lowpass";
      warmth.frequency.value = 2400;
      const feedback = this.own(context.createGain());
      feedback.gain.value = echo.feedback;
      const wet = this.own(context.createGain());
      wet.gain.value = echo.level;
      this.input.connect(delay).connect(warmth);
      warmth.connect(feedback).connect(delay);
      warmth.connect(wet).connect(this.output);
    }
  }

  private own<T extends AudioNode>(node: T): T {
    this.nodes.push(node);
    return node;
  }

  start(): void {
    this.connect();
    this.schedule();
    this.timer = setInterval(() => {
      try {
        this.schedule();
      } catch (error: unknown) {
        this.stop();
        this.onStopped(error);
      }
    }, 100);
  }

  private schedule(): void {
    const now = this.context.currentTime;
    if (!this.loop && now >= this.started + score.duration) {
      this.stop();
      this.onStopped(null);
      return;
    }
    // Skip missed notes after background throttling rather than playing a burst.
    const cycle = Math.max(0, Math.floor((now - this.started) / score.duration));
    if (cycle > this.cycle) {
      this.cycle = cycle;
      this.note = 0;
    }
    for (;;) {
      if (this.note === score.notes.length) break;
      const note = score.notes[this.note];
      if (note === undefined) throw new Error("Thinking score is empty");
      const at = this.started + this.cycle * score.duration + note.at;
      if (at > now + 0.35) break;
      if (at >= now - 0.02) this.play(note, Math.max(now, at));
      this.note++;
      if (this.loop && this.note === score.notes.length) {
        this.note = 0;
        this.cycle++;
      }
    }
  }

  private play(note: (typeof score.notes)[number], at: number): void {
    if (this.voices.size >= 32) {
      const oldest = this.voices.values().next().value;
      if (oldest !== undefined) this.release(oldest, true);
    }
    const frequency = 440 * 2 ** ((note.midi - 69) / 12);
    const decay = 2.4 * Math.sqrt(220 / frequency);
    const voice: PianoVoice = { sources: [], nodes: [] };
    this.voices.add(voice);
    try {
      for (const partial of score.partials) {
        if (frequency * partial.ratio >= this.context.sampleRate * 0.45) continue;
        const oscillator = this.context.createOscillator();
        voice.nodes.push(oscillator);
        oscillator.frequency.value = frequency * partial.ratio;
        const gain = this.context.createGain();
        voice.nodes.push(gain);
        const level = score.gain * note.level * partial.level;
        const heldLevel = level * Math.exp((-note.duration * partial.decay) / decay);
        gain.gain.setValueAtTime(0, at);
        gain.gain.linearRampToValueAtTime(level, at + score.attack);
        gain.gain.exponentialRampToValueAtTime(Math.max(0.000001, heldLevel), at + note.duration);
        gain.gain.exponentialRampToValueAtTime(0.000001, at + note.duration + score.release);
        oscillator.connect(gain).connect(this.input);
        oscillator.start(at);
        voice.sources.push(oscillator);
        oscillator.stop(at + note.duration + score.release);
      }
      const first = voice.sources[0];
      if (first !== undefined) first.onended = () => this.release(voice, false);
    } catch (error: unknown) {
      this.release(voice, true);
      throw error;
    }
  }

  private release(voice: PianoVoice, stop: boolean): void {
    this.voices.delete(voice);
    for (const source of voice.sources) {
      source.onended = null;
      if (stop) source.stop();
    }
    for (const node of voice.nodes) node.disconnect();
  }

  stop(): void {
    if (this.timer !== null) clearInterval(this.timer);
    this.timer = null;
    // Silence every echo and queued note before releasing the voice graph.
    for (const node of this.nodes) node.disconnect();
    for (const voice of this.voices) this.release(voice, true);
  }
}

export class WebAudioThinkingEarcon implements ThinkingEarcon {
  private static activePreview: WebAudioThinkingEarcon | null = null;
  private context: AudioContext | null = null;
  private piano: ThinkingPiano | null = null;
  private activation = 0;

  constructor(private readonly preview?: { onPlaybackChange: (playing: boolean, error: Error | null) => void }) {}

  private failed(error: unknown): void {
    this.setThinking(false);
    this.preview?.onPlaybackChange(false, error instanceof Error ? error : new Error(String(error)));
    console.warn("Could not play thinking audio", error);
  }

  prepare(): void {
    try {
      const context = (this.context ??= new AudioContext());
      const activation = ++this.activation;
      if (context.state === "suspended") {
        void context.resume().catch((error: unknown) => {
          if (this.context === context && this.activation === activation) this.failed(error);
        });
      }
    } catch (error: unknown) {
      this.failed(error);
    }
  }

  setThinking(active: boolean): void {
    if (!active) {
      this.activation++;
      this.piano?.stop();
      this.piano = null;
      if (WebAudioThinkingEarcon.activePreview === this) WebAudioThinkingEarcon.activePreview = null;
      this.preview?.onPlaybackChange(false, null);
      if (this.preview !== undefined) this.closeContext();
      return;
    }
    if (this.context === null || this.piano !== null) return;
    if (this.preview !== undefined) {
      WebAudioThinkingEarcon.activePreview?.setThinking(false);
      WebAudioThinkingEarcon.activePreview = this;
    }
    try {
      const piano = new ThinkingPiano(this.context, this.preview === undefined, (error) => {
        if (this.piano !== piano) return;
        this.piano = null;
        if (error !== null) this.failed(error);
        else this.setThinking(false);
      });
      this.piano = piano;
      piano.start();
      this.preview?.onPlaybackChange(true, null);
    } catch (error: unknown) {
      this.failed(error);
    }
  }

  dispose(): void {
    this.setThinking(false);
    this.closeContext();
  }

  private closeContext(): void {
    const context = this.context;
    this.context = null;
    if (context !== null) {
      void context.close().catch((error: unknown) => console.warn("Could not close thinking audio", error));
    }
  }
}

// Synthesizes a quietly evolving soft pad during half-duplex browser thinking turns.

export interface ThinkingEarcon {
  prepare(): void;
  setThinking(active: boolean): void;
}

// Native audio-clock modulation keeps long waits evolving without timers, buffers, or growing schedules.
class ThinkingPad {
  private readonly nodes: AudioNode[] = [];
  private readonly sources: OscillatorNode[] = [];

  constructor(private readonly context: AudioContext) {}

  private own<T extends AudioNode>(node: T): T {
    this.nodes.push(node);
    return node;
  }

  private oscillator(type: OscillatorType, frequency: number, at: number): OscillatorNode {
    const oscillator = this.own(this.context.createOscillator());
    oscillator.type = type;
    oscillator.frequency.value = frequency;
    oscillator.start(at);
    this.sources.push(oscillator);
    return oscillator;
  }

  private modulate(parameter: AudioParam, frequency: number, depth: number, at: number): void {
    const gain = this.own(this.context.createGain());
    gain.gain.value = depth;
    this.oscillator("sine", frequency, at).connect(gain).connect(parameter);
  }

  start(): void {
    const context = this.context;
    const at = context.currentTime + 0.2;
    const output = this.own(context.createGain());
    output.gain.setValueAtTime(0, context.currentTime);
    output.gain.setValueAtTime(0, at);
    output.gain.linearRampToValueAtTime(1, at + 1.2);
    output.connect(context.destination);

    const warmth = this.own(context.createBiquadFilter());
    warmth.type = "lowpass";
    warmth.frequency.value = 650;
    warmth.Q.value = 0.4;
    warmth.connect(output);
    this.modulate(warmth.frequency, 0.019, 150, at);

    const voices = [
      { frequency: 138.59, gain: 0.021 },
      { frequency: 207.65, gain: 0.016 },
      { frequency: 277.18, gain: 0.009 },
      { frequency: 311.13, gain: 0.006 },
      { frequency: 349.23, gain: 0.004 },
    ];
    for (const [i, { frequency, gain }] of voices.entries()) {
      const level = this.own(context.createGain());
      level.gain.value = gain;
      level.connect(warmth);
      this.modulate(level.gain, 0.031 + i * 0.0073, gain * 0.1, at);
      const core = this.oscillator("triangle", frequency, at);
      this.modulate(core.detune, 0.023 + i * 0.0047, 1.5, at);
      core.connect(level);

      // Quiet shadows add warmth without equal-strength detuned voices beating into silence.
      const shadow = this.own(context.createGain());
      shadow.gain.value = 0.14;
      this.oscillator("triangle", frequency * 1.002, at)
        .connect(shadow)
        .connect(level);
    }
  }

  stop(): void {
    // Disconnect the entire graph first, including any scheduled onset or effect tail.
    for (const node of this.nodes) node.disconnect();
    for (const source of this.sources) source.stop();
    this.nodes.length = 0;
    this.sources.length = 0;
  }
}

export class WebAudioThinkingEarcon implements ThinkingEarcon {
  private context: AudioContext | null = null;
  private pad: ThinkingPad | null = null;

  // Unlock playback in the voice button's user gesture, before asynchronous setup.
  prepare(): void {
    try {
      const context = (this.context ??= new AudioContext());
      if (context.state === "suspended") {
        void context.resume().catch((error: unknown) => console.warn("Could not enable thinking audio", error));
      }
    } catch (error: unknown) {
      console.warn("Could not enable thinking audio", error);
    }
  }

  setThinking(active: boolean): void {
    if (!active) {
      this.pad?.stop();
      this.pad = null;
      return;
    }
    if (this.context === null || this.pad !== null) return;
    const pad = new ThinkingPad(this.context);
    try {
      pad.start();
      this.pad = pad;
    } catch (error: unknown) {
      pad.stop();
      console.warn("Could not start thinking audio", error);
    }
  }
}

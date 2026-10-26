// Measures live microphone audio on a speech-sensitive scale, with gesture-owned Web Audio activation.

// Match the native meter: -60 dBFS is silence; -12 dBFS reaches full height.
export function microphoneLevelFromRms(rms: number): number {
  return Number.isFinite(rms) && rms > 0 ? Math.max(0, Math.min(1, (20 * Math.log10(rms) + 60) / 48)) : 0;
}

export class MicrophoneLevel {
  private context: AudioContext | null = null;
  private source: MediaStreamAudioSourceNode | null = null;
  private analyser: AnalyserNode | null = null;
  private frame: number | null = null;
  private activation = 0;

  constructor(
    private readonly onLevel: (level: number) => void,
    private readonly onError: (error: string | null) => void,
  ) {}

  // Invoke synchronously in the activation event, before discovery or permission awaits.
  prepare(): Promise<void> {
    const activation = ++this.activation;
    try {
      const context = (this.context ??= new AudioContext());
      context.onstatechange = () => {
        if (this.context === context && this.analyser !== null) {
          this.onError(context.state === "running" ? null : "Microphone level meter is paused.");
          if (context.state !== "running") this.onLevel(0);
        }
      };
      return context
        .resume()
        .then(() => {
          // Activation alone cannot repair a failed capture graph. Its error
          // stays visible until start() successfully installs an analyser.
          if (this.context === context && this.activation === activation && this.analyser !== null)
            this.onError(context.state === "running" ? null : "Microphone level meter is paused.");
        })
        .catch((error: unknown) => {
          if (this.context === context && this.activation === activation)
            this.onError(`Microphone level meter unavailable: ${String(error)}`);
        });
    } catch (error) {
      this.onError(`Microphone level meter unavailable: ${String(error)}`);
      return Promise.resolve();
    }
  }

  start(stream: MediaStream, active: () => boolean): void {
    this.stop(true);
    if (this.context === null) void this.prepare();
    const context = this.context;
    if (context === null) return;
    try {
      const analyser = context.createAnalyser();
      this.analyser = analyser;
      analyser.fftSize = 1024;
      this.source = context.createMediaStreamSource(stream);
      this.source.connect(analyser);
      this.onError(context.state === "running" ? null : "Microphone level meter is paused.");
      const samples = new Float32Array(analyser.fftSize);
      const poll = () => {
        if (this.analyser !== analyser) return;
        let level = 0;
        if (active() && context.state === "running") {
          analyser.getFloatTimeDomainData(samples);
          let squares = 0;
          for (const sample of samples) squares += sample * sample;
          level = microphoneLevelFromRms(Math.sqrt(squares / samples.length));
        }
        this.onLevel(level);
        this.frame = requestAnimationFrame(poll);
      };
      this.frame = requestAnimationFrame(poll);
    } catch (error) {
      this.stop(true);
      this.onError(`Microphone level meter unavailable: ${String(error)}`);
    }
  }

  stop(keepContext: boolean): void {
    if (this.frame !== null) cancelAnimationFrame(this.frame);
    this.frame = null;
    this.source?.disconnect();
    this.analyser?.disconnect();
    this.source = null;
    this.analyser = null;
    this.onLevel(0);
    if (!keepContext) {
      const context = this.context;
      this.context = null;
      if (context !== null) context.onstatechange = null;
      this.onError(null);
      void context?.close().catch((error: unknown) => console.warn("Could not close microphone meter", error));
    }
  }
}

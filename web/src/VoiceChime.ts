// Synthesizes the browser voice-mode connection and disconnection chimes.

const CHIME_GAIN = 0.12;
const NOTE_DURATION_SECONDS = 0.12;
const NOTE_GAP_SECONDS = 0.035;
const ATTACK_SECONDS = 0.012;
const CONNECT_FREQUENCIES = [523.25, 659.25] as const;
const DISCONNECT_FREQUENCIES = [659.25, 523.25] as const;

export interface VoiceChimePlayer {
  prepare(): void;
  playConnected(): void;
  playDisconnected(): void;
}

export class WebAudioVoiceChime implements VoiceChimePlayer {
  private context: AudioContext | null = null;

  prepare(): void {
    const context = this.audioContext();
    if (context.state === "suspended") {
      void context.resume().catch((error: unknown) => console.warn("Could not enable voice chimes", error));
    }
  }

  playConnected(): void {
    this.play(CONNECT_FREQUENCIES);
  }

  playDisconnected(): void {
    this.play(DISCONNECT_FREQUENCIES);
  }

  private audioContext(): AudioContext {
    this.context ??= new AudioContext();
    return this.context;
  }

  private play(frequencies: readonly number[]): void {
    const context = this.audioContext();
    const start = context.currentTime;
    frequencies.forEach((frequency, index) => {
      const noteStart = start + index * (NOTE_DURATION_SECONDS + NOTE_GAP_SECONDS);
      const noteEnd = noteStart + NOTE_DURATION_SECONDS;
      const oscillator = context.createOscillator();
      const gain = context.createGain();
      oscillator.type = "sine";
      oscillator.frequency.setValueAtTime(frequency, noteStart);
      gain.gain.setValueAtTime(0, noteStart);
      gain.gain.linearRampToValueAtTime(CHIME_GAIN, noteStart + ATTACK_SECONDS);
      gain.gain.linearRampToValueAtTime(0, noteEnd);
      oscillator.connect(gain).connect(context.destination);
      oscillator.start(noteStart);
      oscillator.stop(noteEnd);
    });
  }
}

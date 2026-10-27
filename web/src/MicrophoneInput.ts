// Attenuates outgoing WebRTC microphone audio during assistant speech.

import { AudioGain } from "./AudioGain";

export class MicrophoneInput {
  private context: AudioContext | null = null;
  private gains = new Map<GainNode, AudioGain>();
  private speaking = false;

  async prepare(): Promise<void> {
    const context = (this.context ??= new AudioContext());
    await context.resume();
  }

  connect(stream: MediaStream): { stream: MediaStream; close: () => void } {
    const context = this.context;
    if (context === null || context.state !== "running") throw new Error("Microphone audio processing is paused.");
    const source = context.createMediaStreamSource(stream);
    const gain = context.createGain();
    const destination = context.createMediaStreamDestination();
    // -20 dB reduces speaker leakage while leaving louder user interruptions possible.
    const envelope = new AudioGain(gain.gain, this.speaking ? 0.1 : 1);
    source.connect(gain);
    gain.connect(destination);
    this.gains.set(gain, envelope);
    return {
      stream: destination.stream,
      close: () => {
        source.disconnect();
        gain.disconnect();
        destination.stream.getTracks().forEach((track) => track.stop());
        this.gains.delete(gain);
      },
    };
  }

  setSpeaking(speaking: boolean): void {
    if (this.speaking === speaking) return;
    this.speaking = speaking;
    const at = this.context?.currentTime;
    if (at === undefined) return;
    for (const gain of this.gains.values()) gain.rampTo(speaking ? 0.1 : 1, speaking ? 0.03 : 0.1, at);
  }

  close(): void {
    const context = this.context;
    this.context = null;
    this.speaking = false;
    void context?.close().catch((error: unknown) => console.warn("Could not close microphone processing", error));
  }
}

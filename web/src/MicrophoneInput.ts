// Attenuates outgoing WebRTC microphone audio during assistant speech.

export class MicrophoneInput {
  private context: AudioContext | null = null;
  private gains = new Set<GainNode>();
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
    gain.gain.value = this.speaking ? 0.1 : 1;
    source.connect(gain);
    gain.connect(destination);
    this.gains.add(gain);
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
    this.speaking = speaking;
    for (const gain of this.gains) gain.gain.value = speaking ? 0.1 : 1;
  }

  close(): void {
    const context = this.context;
    this.context = null;
    this.speaking = false;
    void context?.close().catch((error: unknown) => console.warn("Could not close microphone processing", error));
  }
}

// Tests the browser voice-mode chime notes and ordering.

import { afterEach, beforeEach, describe, it } from "node:test";
import { expect, vi } from "../tests/expect";

import { WebAudioVoiceChime } from "./VoiceChime";

const frequencies: number[] = [];

class FakeAudioContext {
  readonly currentTime = 2;
  readonly destination = {} as AudioDestinationNode;
  readonly state = "running";

  createGain(): GainNode {
    return {
      connect: (destination: AudioNode) => destination,
      gain: {
        linearRampToValueAtTime: () => {},
        setValueAtTime: () => {},
      },
    } as unknown as GainNode;
  }

  createOscillator(): OscillatorNode {
    return {
      connect: (destination: AudioNode) => destination,
      frequency: {
        setValueAtTime: (value: number) => frequencies.push(value),
      },
      start: () => {},
      stop: () => {},
      type: "sine",
    } as unknown as OscillatorNode;
  }

  resume(): Promise<void> {
    return Promise.resolve();
  }
}

describe("WebAudioVoiceChime", () => {
  beforeEach(() => {
    frequencies.length = 0;
    vi.stubGlobal("AudioContext", FakeAudioContext as unknown as typeof AudioContext);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("uses ascending and descending two-note cues", () => {
    const chime = new WebAudioVoiceChime();

    chime.playConnected();
    chime.playDisconnected();

    expect(frequencies).toEqual([523.25, 659.25, 659.25, 523.25]);
  });
});

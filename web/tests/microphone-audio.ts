// Web Audio capture doubles expose real sample windows, activation, and retired graphs to voice tests.

import { vi } from "./expect";

export class FakeMicrophoneContext {
  static instances: FakeMicrophoneContext[] = [];
  state = "suspended";
  onstatechange: (() => void) | null = null;
  sample = 0.01;
  source = { connect: vi.fn(), disconnect: vi.fn() };
  analyser = {
    fftSize: 0,
    getFloatTimeDomainData: (samples: Float32Array) => samples.fill(this.sample),
    disconnect: vi.fn(),
  };
  resume = vi.fn(async () => {
    this.state = "running";
  });
  close = vi.fn(async () => {
    this.state = "closed";
  });
  constructor() {
    FakeMicrophoneContext.instances.push(this);
  }
  createAnalyser() {
    return this.analyser;
  }
  createMediaStreamSource() {
    return this.source;
  }
}

export function microphoneFrames(): () => void {
  let next = 0;
  const frames = new Map<number, FrameRequestCallback>();
  vi.stubGlobal(
    "requestAnimationFrame",
    vi.fn((callback: FrameRequestCallback) => {
      frames.set(++next, callback);
      return next;
    }),
  );
  vi.stubGlobal("cancelAnimationFrame", (id: number) => frames.delete(id));
  return () => {
    const current = [...frames.values()];
    frames.clear();
    for (const callback of current) callback(0);
  };
}

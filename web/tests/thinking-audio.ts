// Shared Web Audio doubles for piano scheduling and preview lifecycle tests.

import { vi } from "./expect";

class FakeParam {
  value = 0;
  cancelScheduledValues = vi.fn();
  setValueAtTime = vi.fn();
  linearRampToValueAtTime = vi.fn();
  exponentialRampToValueAtTime = vi.fn();
}

class FakeNode {
  connect = vi.fn(() => this);
  disconnect = vi.fn();
}

class FakeOscillator extends FakeNode {
  frequency = new FakeParam();
  onended: (() => void) | null = null;
  ended = false;
  started = false;
  end = Infinity;
  start = vi.fn(() => {
    this.started = true;
  });
  stop = vi.fn((at?: number) => {
    if (!this.started) throw new Error("Cannot stop an oscillator before start");
    this.end = at ?? 0;
  });
}

export class FakeGain extends FakeNode {
  gain = new FakeParam();
}

class FakeFilter extends FakeNode {
  type = "lowpass";
  frequency = new FakeParam();
  Q = new FakeParam();
}

class FakeDelay extends FakeNode {
  delayTime = new FakeParam();
}

export class FakeAudioContext {
  static instances: FakeAudioContext[] = [];
  currentTime = 2;
  sampleRate = 24000;
  state = "suspended";
  destination = {};
  nodes: FakeNode[] = [];
  sources: FakeOscillator[] = [];
  resume = vi.fn(() => Promise.resolve());
  close = vi.fn(() => Promise.resolve());

  constructor() {
    FakeAudioContext.instances.push(this);
  }

  createOscillator(): OscillatorNode {
    const node = new FakeOscillator();
    this.nodes.push(node);
    this.sources.push(node);
    return node as unknown as OscillatorNode;
  }

  createGain(): GainNode {
    const node = new FakeGain();
    this.nodes.push(node);
    return node as unknown as GainNode;
  }

  createBiquadFilter(): BiquadFilterNode {
    const node = new FakeFilter();
    this.nodes.push(node);
    return node as unknown as BiquadFilterNode;
  }

  createDelay(): DelayNode {
    const node = new FakeDelay();
    this.nodes.push(node);
    return node as unknown as DelayNode;
  }
}

export function advance(context: FakeAudioContext, seconds: number): void {
  for (let step = 0; step < seconds * 10; step++) {
    context.currentTime += 0.1;
    for (const source of context.sources) {
      if (!source.ended && source.end <= context.currentTime) {
        source.ended = true;
        source.onended?.();
      }
    }
    vi.advanceTimersByTime(100);
  }
}

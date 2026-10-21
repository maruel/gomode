// Tests live thinking-pad scheduling, bounded modulation, teardown, and restart.

import { afterEach, beforeEach, describe, it } from "node:test";
import { ok } from "node:assert/strict";
import { expect, vi } from "../tests/expect";
import { WebAudioThinkingEarcon } from "./ThinkingEarcon";

class FakeParam {
  value = 0;
  setValueAtTime = vi.fn();
  linearRampToValueAtTime = vi.fn();
}

class FakeNode {
  connect = vi.fn(() => this);
  disconnect = vi.fn();
}

class FakeOscillator extends FakeNode {
  type = "sine";
  frequency = new FakeParam();
  detune = new FakeParam();
  start = vi.fn();
  stop = vi.fn();
}

class FakeGain extends FakeNode {
  gain = new FakeParam();
}

class FakeFilter extends FakeNode {
  type = "lowpass";
  frequency = new FakeParam();
  Q = new FakeParam();
}

class FakeAudioContext {
  static instances: FakeAudioContext[] = [];
  currentTime = 2;
  state = "suspended";
  destination = {};
  nodes: FakeNode[] = [];
  sources: FakeOscillator[] = [];
  resume = vi.fn(() => Promise.resolve());

  constructor() {
    FakeAudioContext.instances.push(this);
  }

  createOscillator(): OscillatorNode {
    const source = new FakeOscillator();
    this.nodes.push(source);
    this.sources.push(source);
    return source as unknown as OscillatorNode;
  }

  createGain(): GainNode {
    const gain = new FakeGain();
    this.nodes.push(gain);
    return gain as unknown as GainNode;
  }

  createBiquadFilter(): BiquadFilterNode {
    const filter = new FakeFilter();
    this.nodes.push(filter);
    return filter as unknown as BiquadFilterNode;
  }
}

describe("WebAudioThinkingEarcon", () => {
  beforeEach(() => {
    FakeAudioContext.instances = [];
    vi.stubGlobal("AudioContext", FakeAudioContext);
  });
  afterEach(() => vi.unstubAllGlobals());

  it("unlocks immediately, preserves the grace period, and fades in only once per turn", () => {
    const earcon = new WebAudioThinkingEarcon();
    earcon.setThinking(true);
    expect(FakeAudioContext.instances).toHaveLength(0);
    earcon.prepare();
    const context = FakeAudioContext.instances[0];
    ok(context);
    expect(context.resume).toHaveBeenCalledOnce();
    expect(context.nodes).toHaveLength(0);
    earcon.setThinking(true);
    const count = context.nodes.length;
    const output = context.nodes[0] as FakeGain;
    expect(output.gain.setValueAtTime).toHaveBeenCalledWith(0, 2.2);
    const ramp = output.gain.linearRampToValueAtTime.mock.calls[0];
    ok(ramp);
    expect(ramp[0]).toBe(1);
    expect(ramp[1]).toBeCloseTo(3.4);
    for (const source of context.sources) expect(source.start).toHaveBeenCalledExactlyOnceWith(2.2);
    context.currentTime = 1000;
    earcon.setThinking(true);
    expect(context.nodes).toHaveLength(count);
    expect(output.gain.linearRampToValueAtTime).toHaveBeenCalledOnce();
    expect(context.sources.filter((source) => source.type === "triangle").length).toBeGreaterThan(1);
    expect(context.sources.some((source) => source.frequency.value < 0.1)).toBe(true);
    earcon.setThinking(false);
  });

  it("synchronously silences all carriers and modulators before grace expiry and supports restart", () => {
    const earcon = new WebAudioThinkingEarcon();
    earcon.prepare();
    const context = FakeAudioContext.instances[0];
    ok(context);
    earcon.setThinking(true);
    const firstNodes = [...context.nodes];
    const firstSources = [...context.sources];
    earcon.setThinking(false);
    earcon.setThinking(false);
    for (const node of firstNodes) expect(node.disconnect).toHaveBeenCalledOnce();
    for (const source of firstSources) expect(source.stop).toHaveBeenCalledExactlyOnceWith();
    context.currentTime = 50;
    earcon.setThinking(true);
    for (const source of context.sources.slice(firstSources.length)) {
      expect(source.start).toHaveBeenCalledExactlyOnceWith(50.2);
    }
    earcon.setThinking(false);
    for (const node of context.nodes) expect(node.disconnect).toHaveBeenCalledOnce();
    for (const source of context.sources) expect(source.stop).toHaveBeenCalledExactlyOnceWith();
    expect(FakeAudioContext.instances).toHaveLength(1);
  });

  it("reports unavailable audio and rejected unlocks without disrupting voice setup", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    try {
      vi.stubGlobal(
        "AudioContext",
        class {
          constructor() {
            throw new Error("Audio device unavailable");
          }
        },
      );
      const earcon = new WebAudioThinkingEarcon();
      earcon.prepare();
      earcon.setThinking(true);
      expect(warn).toHaveBeenCalledOnce();
      vi.stubGlobal("AudioContext", FakeAudioContext);
      earcon.prepare();
      const context = FakeAudioContext.instances[0];
      ok(context);
      context.resume.mockRejectedValueOnce(new Error("Activation required"));
      earcon.prepare();
      await new Promise<void>((resolve) => setImmediate(resolve));
      expect(warn).toHaveBeenCalledTimes(2);
      earcon.setThinking(true);
      expect(context.sources.length).toBeGreaterThan(0);
      earcon.setThinking(false);
    } finally {
      warn.mockRestore();
    }
  });

  it("releases a partially constructed graph on failure and allows retry", () => {
    const earcon = new WebAudioThinkingEarcon();
    earcon.prepare();
    const context = FakeAudioContext.instances[0];
    ok(context);
    const create = context.createOscillator.bind(context);
    let calls = 0;
    const fail = vi.spyOn(context, "createOscillator").mockImplementation(() => {
      if (++calls === 3) throw new Error("Audio device unavailable");
      return create();
    });
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    try {
      earcon.setThinking(true);
      expect(warn).toHaveBeenCalledOnce();
      for (const node of context.nodes) expect(node.disconnect).toHaveBeenCalledOnce();
      for (const source of context.sources) expect(source.stop).toHaveBeenCalledOnce();
      fail.mockRestore();
      earcon.setThinking(true);
      expect(context.sources.length).toBeGreaterThan(0);
      earcon.setThinking(false);
    } finally {
      fail.mockRestore();
      warn.mockRestore();
    }
  });
});

// Tests bounded live piano scheduling, echo cancellation, failures, and score-loop restart.

import { afterEach, beforeEach, describe, it } from "node:test";
import { ok } from "node:assert/strict";
import { expect, vi } from "../tests/expect";
import { WebAudioThinkingEarcon } from "./ThinkingEarcon";

import type { FakeGain } from "../tests/thinking-audio";
import { FakeAudioContext, advance } from "../tests/thinking-audio";

describe("WebAudioThinkingEarcon", () => {
  it("disconnects a note whose gain allocation fails before the oscillator starts", () => {
    const earcon = new WebAudioThinkingEarcon();
    earcon.prepare();
    const context = FakeAudioContext.instances[0];
    ok(context);
    earcon.setThinking(true);
    const allocation = vi.spyOn(context, "createGain").mockImplementationOnce(() => {
      throw new Error("Unavailable");
    });
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    try {
      advance(context, 0.2);
      for (const node of context.nodes) expect(node.disconnect).toHaveBeenCalledOnce();
      for (const source of context.sources) expect(source.stop).not.toHaveBeenCalled();
      expect(warn).toHaveBeenCalledOnce();
    } finally {
      allocation.mockRestore();
      warn.mockRestore();
      earcon.setThinking(false);
    }
  });
  it("hands preview playback to another settings view instead of overlapping the music", () => {
    const firstChanged = vi.fn();
    const first = new WebAudioThinkingEarcon({ onPlaybackChange: firstChanged });
    const secondChanged = vi.fn();
    const second = new WebAudioThinkingEarcon({ onPlaybackChange: secondChanged });
    first.prepare();
    second.prepare();
    first.setThinking(true);
    second.setThinking(true);
    expect(firstChanged).toHaveBeenLastCalledWith(false, null);
    expect(secondChanged).toHaveBeenLastCalledWith(true, null);
    first.dispose();
    second.dispose();
  });
  it("finishes a preview once, resets its control, and closes its audio context on disposal", () => {
    const changed = vi.fn();
    const preview = new WebAudioThinkingEarcon({ onPlaybackChange: changed });
    preview.prepare();
    const context = FakeAudioContext.instances[0];
    ok(context);
    preview.setThinking(true);
    expect(changed).toHaveBeenLastCalledWith(true, null);
    advance(context, 190);
    expect(changed).toHaveBeenLastCalledWith(false, null);
    const count = context.sources.length;
    advance(context, 3);
    expect(context.sources).toHaveLength(count);
    preview.dispose();
    expect(context.close).toHaveBeenCalledOnce();
  });

  it("ignores unlock failures belonging to a stopped preview", async () => {
    const changed = vi.fn();
    const preview = new WebAudioThinkingEarcon({ onPlaybackChange: changed });
    preview.prepare();
    const context = FakeAudioContext.instances[0];
    ok(context);
    let rejectUnlock: (error: Error) => void = () => {
      throw new Error("Unlock not requested");
    };
    context.resume.mockImplementationOnce(
      () =>
        new Promise<void>((_, reject) => {
          rejectUnlock = reject;
        }),
    );
    preview.prepare();
    preview.setThinking(true);
    preview.setThinking(false);
    context.state = "running";
    preview.prepare();
    preview.setThinking(true);
    rejectUnlock(new Error("Old unlock failed"));
    await Promise.resolve();
    expect(changed).toHaveBeenLastCalledWith(true, null);
    preview.dispose();
  });
  beforeEach(() => {
    FakeAudioContext.instances = [];
    vi.stubGlobal("AudioContext", FakeAudioContext);
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("unlocks in the gesture, fades in once, and schedules notes only near playback", () => {
    const earcon = new WebAudioThinkingEarcon();
    earcon.setThinking(true);
    expect(FakeAudioContext.instances).toHaveLength(0);
    earcon.prepare();
    const context = FakeAudioContext.instances[0];
    ok(context);
    expect(context.resume).toHaveBeenCalledOnce();
    earcon.setThinking(true);
    const output = context.nodes[0] as FakeGain;
    expect(output.gain.setValueAtTime).toHaveBeenCalledWith(0, 2.2);
    const ramp = output.gain.linearRampToValueAtTime.mock.calls[0];
    ok(ramp);
    expect(ramp[0]).toBe(1);
    expect(ramp[1]).toBeCloseTo(3.4);
    expect(context.sources).toHaveLength(0);
    advance(context, 0.2);
    expect(context.sources.length).toBeGreaterThan(0);
    for (const source of context.sources) {
      expect(source.start).toHaveBeenCalledWith(2.5);
      expect(source.frequency.value).toBeGreaterThan(200);
    }
    const count = context.nodes.length;
    earcon.setThinking(true);
    expect(context.nodes).toHaveLength(count);
    earcon.setThinking(false);
  });

  it("silences echoes and queued notes synchronously and can restart before onset", () => {
    const earcon = new WebAudioThinkingEarcon();
    earcon.prepare();
    const context = FakeAudioContext.instances[0];
    ok(context);
    earcon.setThinking(true);
    advance(context, 0.2);
    const firstNodes = [...context.nodes];
    const firstSources = [...context.sources];
    earcon.setThinking(false);
    earcon.setThinking(false);
    for (const node of firstNodes) expect(node.disconnect).toHaveBeenCalledOnce();
    for (const source of firstSources) expect(source.stop).toHaveBeenLastCalledWith();
    advance(context, 3);
    expect(context.nodes).toHaveLength(firstNodes.length);
    earcon.setThinking(true);
    earcon.setThinking(false);
    advance(context, 1);
    for (const node of context.nodes) expect(node.disconnect).toHaveBeenCalledOnce();
    expect(context.sources).toHaveLength(firstSources.length);
  });

  it("keeps voices bounded through the full score, loops, and skips missed background notes", () => {
    const earcon = new WebAudioThinkingEarcon();
    earcon.prepare();
    const context = FakeAudioContext.instances[0];
    ok(context);
    earcon.setThinking(true);
    for (let second = 0; second < 190; second++) {
      advance(context, 1);
      expect(context.nodes.filter((node) => node.disconnect.mock.calls.length === 0).length).toBeLessThan(410);
    }
    const count = context.sources.length;
    advance(context, 2);
    expect(context.sources.length).toBeGreaterThan(count);
    context.currentTime += 3600;
    const before = context.sources.length;
    advance(context, 0.1);
    expect(context.sources.length - before).toBeLessThan(30);
    earcon.setThinking(false);
    for (const node of context.nodes) expect(node.disconnect).toHaveBeenCalledOnce();
  });

  it("cleans up partial graphs and failed scheduled notes without interrupting the voice session", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const earcon = new WebAudioThinkingEarcon();
    earcon.prepare();
    const context = FakeAudioContext.instances[0];
    ok(context);
    const fail = vi.spyOn(context, "createDelay").mockImplementationOnce(() => {
      throw new Error("Unavailable");
    });
    earcon.setThinking(true);
    for (const node of context.nodes) expect(node.disconnect).toHaveBeenCalledOnce();
    fail.mockRestore();
    earcon.setThinking(true);
    const oscillator = vi.spyOn(context, "createOscillator").mockImplementationOnce(() => {
      throw new Error("Unavailable");
    });
    advance(context, 0.2);
    expect(warn).toHaveBeenCalledTimes(2);
    for (const node of context.nodes) expect(node.disconnect).toHaveBeenCalledOnce();
    oscillator.mockRestore();
    earcon.setThinking(false);
    earcon.setThinking(true);
    advance(context, 0.3);
    expect(context.sources.length).toBeGreaterThan(0);
    earcon.setThinking(false);
    warn.mockRestore();
  });

  it("reports unavailable audio and rejected unlocks without disrupting setup", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    vi.stubGlobal(
      "AudioContext",
      class {
        constructor() {
          throw new Error("Unavailable");
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
    await Promise.resolve();
    expect(warn).toHaveBeenCalledTimes(2);
    earcon.setThinking(true);
    earcon.setThinking(false);
    warn.mockRestore();
  });
});

// Tests microphone activation recovery, real sample scaling, graph replacement, and late callback isolation.

import { afterEach, beforeEach, it } from "node:test";
import { expect, vi } from "../tests/expect";
import { FakeMicrophoneContext, microphoneFrames } from "../tests/microphone-audio";
import { MicrophoneLevel, microphoneLevelFromRms } from "./MicrophoneLevel";

beforeEach(() => {
  FakeMicrophoneContext.instances = [];
  vi.stubGlobal("AudioContext", FakeMicrophoneContext);
});
afterEach(() => vi.unstubAllGlobals());

it("makes quiet speech visible and bounds silence, invalid samples and clipping", () => {
  expect(microphoneLevelFromRms(0.01)).toBeGreaterThan(0.3);
  expect(microphoneLevelFromRms(0)).toBe(0);
  expect(microphoneLevelFromRms(NaN)).toBe(0);
  expect(microphoneLevelFromRms(1)).toBe(1);
});

it("retries suspended audio in the same context and prevents late activation errors after teardown", async () => {
  const poll = microphoneFrames();
  const level = vi.fn();
  const error = vi.fn();
  const meter = new MicrophoneLevel(level, error);
  meter.prepare();
  const context = FakeMicrophoneContext.instances[0]!;
  await Promise.resolve();
  meter.start({} as MediaStream, () => true);
  poll();
  expect(level).toHaveBeenLastCalledWith(expect.any(Number));
  expect(level.mock.calls.at(-1)![0]).toBeGreaterThan(0.3);
  context.state = "suspended";
  context.onstatechange?.();
  expect(level).toHaveBeenLastCalledWith(0);
  expect(error).toHaveBeenLastCalledWith(expect.stringContaining("paused"));
  meter.prepare();
  await Promise.resolve();
  poll();
  expect(error).toHaveBeenLastCalledWith(null);
  expect(level.mock.calls.at(-1)![0]).toBeGreaterThan(0.3);
  context.resume.mockRejectedValueOnce(new Error("blocked"));
  meter.prepare();
  meter.stop(false);
  await Promise.resolve();
  await Promise.resolve();
  expect(error).toHaveBeenLastCalledWith(null);
  expect(context.close).toHaveBeenCalledOnce();
});

it("disconnects replaced graphs and ignores their already queued callbacks", async () => {
  microphoneFrames();
  const level = vi.fn();
  const meter = new MicrophoneLevel(level, vi.fn());
  meter.prepare();
  await Promise.resolve();
  meter.start({} as MediaStream, () => true);
  const retired = vi.mocked(requestAnimationFrame).mock.calls.at(-1)?.[0];
  const context = FakeMicrophoneContext.instances[0]!;
  const oldSource = context.source;
  context.source = { connect: vi.fn(), disconnect: vi.fn() };
  context.analyser = { fftSize: 0, getFloatTimeDomainData: (samples) => samples.fill(0), disconnect: vi.fn() };
  meter.start({} as MediaStream, () => false);
  expect(oldSource.disconnect).toHaveBeenCalledOnce();
  level.mockClear();
  retired?.(0);
  expect(level).not.toHaveBeenCalled();
  meter.stop(false);
});

it("surfaces activation failure and ignores an older failure after a successful user retry", async () => {
  microphoneFrames();
  const error = vi.fn();
  const meter = new MicrophoneLevel(vi.fn(), error);
  meter.prepare();
  meter.start({} as MediaStream, () => true);
  const context = FakeMicrophoneContext.instances[0]!;
  await Promise.resolve();
  context.resume.mockRejectedValueOnce(new Error("blocked"));
  meter.prepare();
  await Promise.resolve();
  await Promise.resolve();
  expect(error).toHaveBeenLastCalledWith(expect.stringContaining("blocked"));
  let rejectOld!: (reason: Error) => void;
  context.resume.mockReturnValueOnce(
    new Promise((_resolve, reject) => {
      rejectOld = reject;
    }),
  );
  meter.prepare();
  meter.prepare();
  await Promise.resolve();
  rejectOld(new Error("retired attempt"));
  await Promise.resolve();
  await Promise.resolve();
  expect(error).toHaveBeenLastCalledWith(null);
  meter.stop(false);
});

it("keeps graph failure visible after pending activation succeeds and clears it only when retry creates a graph", async () => {
  microphoneFrames();
  const error = vi.fn();
  const meter = new MicrophoneLevel(vi.fn(), error);
  const activation = meter.prepare();
  const context = FakeMicrophoneContext.instances[0]!;
  vi.spyOn(context, "createMediaStreamSource").mockImplementationOnce(() => {
    throw new Error("capture graph unavailable");
  });
  meter.start({} as MediaStream, () => true);
  expect(error).toHaveBeenLastCalledWith(expect.stringContaining("capture graph unavailable"));
  await activation;
  expect(error).toHaveBeenLastCalledWith(expect.stringContaining("capture graph unavailable"));
  const retry = meter.prepare();
  meter.start({} as MediaStream, () => true);
  await retry;
  expect(error).toHaveBeenLastCalledWith(null);
  meter.stop(false);
});

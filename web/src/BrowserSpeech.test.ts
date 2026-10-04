// Tests browser speech platform configuration, transcript snapshots, and lifecycle isolation.

import { afterEach, beforeEach, describe, it } from "node:test";
import { expect, vi } from "../tests/expect";
import { FakeRecognition, fakeSynthesis, installBrowserSpeech } from "../tests/browser-speech";
import { BrowserSpeech, browserSpeechAvailable, browserSpeechAvoidsChimes } from "./BrowserSpeech";

let restore: () => void;
let speech: BrowserSpeech;
const callbacks = { listening: vi.fn(), interim: vi.fn(), final: vi.fn(), error: vi.fn() };

beforeEach(() => {
  vi.clearAllMocks();
  restore = installBrowserSpeech("Chrome desktop");
  speech = new BrowserSpeech("en-US", callbacks);
});
afterEach(() => {
  speech.close();
  restore();
  vi.useRealTimers();
});

describe("BrowserSpeech", () => {
  it("uses desktop continuous recognition and an explicit regional language", () => {
    speech.listen();
    const recognition = FakeRecognition.instances[0];
    expect(recognition).toMatchObject({
      continuous: true,
      interimResults: true,
      maxAlternatives: 1,
      lang: "en-US",
      unspokenPunctuation: true,
    });
    expect(callbacks.listening).toHaveBeenCalledWith(true);
  });

  it("uses one-shot Android recognition and replaces overlapping partial hypotheses", () => {
    Object.defineProperty(navigator, "userAgent", { configurable: true, value: "Chrome Android" });
    speech.listen();
    const recognition = FakeRecognition.instances[0];
    expect(recognition?.continuous).toBe(false);
    recognition?.result([["I", false]]);
    recognition?.result([["I love", false]]);
    recognition?.result([["I love very very", true]]);
    recognition?.result([["I love very very", true]]);
    expect(callbacks.interim.mock.calls.map(([text]) => text)).toEqual(["", "I", "I love", "I love very very"]);
    expect(callbacks.final).toHaveBeenCalledExactlyOnceWith("I love very very");
    expect(recognition?.aborted).toBe(true);
  });

  it("reads the complete desktop result snapshot without duplicating interim text", () => {
    vi.useFakeTimers();
    speech.listen();
    const recognition = FakeRecognition.instances[0];
    recognition?.result([["hello", false]]);
    recognition?.result([
      ["hello ", true],
      ["world", true],
    ]);
    vi.advanceTimersByTime(800);
    expect(callbacks.final).toHaveBeenCalledExactlyOnceWith("hello world");
  });

  it("retains final segments while the continuous snapshot has an interim suffix", () => {
    vi.useFakeTimers();
    speech.listen();
    const recognition = FakeRecognition.instances[0];
    recognition?.result([
      ["Turn on", true],
      [" the lights", false],
    ]);
    vi.advanceTimersByTime(1000);
    expect(callbacks.final).not.toHaveBeenCalled();
    recognition?.result([
      ["Turn on", true],
      [" the lights", true],
    ]);
    vi.advanceTimersByTime(800);
    expect(callbacks.final).toHaveBeenCalledExactlyOnceWith("Turn on the lights");
  });

  it("preserves committed speech when end abandons a trailing interim result", () => {
    speech.listen();
    const recognition = FakeRecognition.instances[0];
    recognition?.result([
      ["Turn on", true],
      [" the lights", false],
    ]);
    recognition?.end();
    expect(callbacks.final).toHaveBeenCalledExactlyOnceWith("Turn on");
  });

  it("cycles healthy silence only after end without reporting an error", () => {
    vi.useFakeTimers();
    speech.listen();
    vi.advanceTimersByTime(30000);
    expect(callbacks.error).not.toHaveBeenCalled();
    expect(FakeRecognition.instances[0]?.aborted).toBe(true);
    FakeRecognition.instances[0]?.end();
    vi.advanceTimersByTime(250);
    expect(FakeRecognition.instances).toHaveLength(2);
    expect(callbacks.error).not.toHaveBeenCalled();
  });

  it("bounds post-start stalls and aborts that never end without overlapping engines", () => {
    vi.useFakeTimers();
    speech.listen();
    vi.advanceTimersByTime(30000);
    expect(callbacks.error).not.toHaveBeenCalled();
    vi.advanceTimersByTime(5000);
    expect(callbacks.error).toHaveBeenCalledWith(expect.stringContaining("did not stop"));
    speech.listen();
    expect(FakeRecognition.instances).toHaveLength(1);
  });

  it("restarts only after end and stops restarting after mute or disposal", () => {
    vi.useFakeTimers();
    speech.listen();
    FakeRecognition.instances[0]?.end();
    expect(FakeRecognition.instances).toHaveLength(1);
    vi.advanceTimersByTime(250);
    expect(FakeRecognition.instances).toHaveLength(2);
    speech.stopListening();
    FakeRecognition.instances[1]?.end();
    vi.advanceTimersByTime(1000);
    expect(FakeRecognition.instances).toHaveLength(2);
  });

  it("waits for an aborted recognizer's end before resuming", () => {
    vi.useFakeTimers();
    speech.listen();
    speech.stopListening();
    speech.listen();
    expect(FakeRecognition.instances).toHaveLength(1);
    FakeRecognition.instances[0]?.result([["stale", true]]);
    expect(callbacks.final).not.toHaveBeenCalled();
    FakeRecognition.instances[0]?.end();
    vi.advanceTimersByTime(250);
    expect(FakeRecognition.instances).toHaveLength(2);
  });

  it("reports permission failure without restart loops", () => {
    vi.useFakeTimers();
    speech.listen();
    FakeRecognition.instances[0]?.onerror?.({ error: "not-allowed" });
    FakeRecognition.instances[0]?.end();
    vi.advanceTimersByTime(1000);
    expect(callbacks.error).toHaveBeenCalledWith(expect.stringContaining("not-allowed"));
    expect(FakeRecognition.instances).toHaveLength(1);
  });

  it("bounds a recognizer that never starts or ends", () => {
    vi.useFakeTimers();
    FakeRecognition.signalsStart = false;
    speech.listen();
    vi.advanceTimersByTime(5000);
    expect(callbacks.error).toHaveBeenCalledWith(expect.stringContaining("did not start"));
  });

  it("speaks in the selected language and ignores completion after disposal", () => {
    const done = vi.fn();
    speech.speak("Hello.", done);
    expect(fakeSynthesis.spoken[0]?.lang).toBe("en-US");
    speech.close();
    fakeSynthesis.complete();
    expect(done).not.toHaveBeenCalled();
    expect(fakeSynthesis.cancel).toHaveBeenCalledOnce();
  });

  it("uses Safari's prefixed constructor with continuous results", () => {
    Reflect.deleteProperty(window, "SpeechRecognition");
    Object.defineProperty(window, "webkitSpeechRecognition", { configurable: true, value: FakeRecognition });
    Object.defineProperty(navigator, "userAgent", { configurable: true, value: "Macintosh Safari" });
    speech.listen();
    expect(FakeRecognition.instances[0]?.continuous).toBe(true);
  });

  it("does not advertise ordinary Firefox and avoids iOS UI sounds", () => {
    Object.defineProperty(navigator, "userAgent", { configurable: true, value: "Firefox/150" });
    expect(browserSpeechAvailable()).toBe(false);
    Object.defineProperty(navigator, "userAgent", { configurable: true, value: "iPhone Safari" });
    expect(browserSpeechAvailable()).toBe(true);
    expect(browserSpeechAvoidsChimes()).toBe(true);
  });
});

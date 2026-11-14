// Tests browser speech platform configuration, transcript snapshots, and lifecycle isolation.

import { afterEach, beforeEach, describe, it } from "node:test";
import { expect, vi } from "../tests/expect";
import { FakeRecognition, fakeSynthesis, installBrowserSpeech } from "../tests/browser-speech";
import { BrowserSpeech, browserRecognitionAvailable, browserSpeechAvoidsChimes } from "./BrowserSpeech";

let restore: () => void;
let speech: BrowserSpeech;
const pending: string[] = [];
const callbacks = {
  nextSpeech: vi.fn(() => pending.shift() ?? null),
  listening: vi.fn(),
  speaking: vi.fn(),
  interim: vi.fn(),
  final: vi.fn(),
  error: vi.fn(),
};

function speak(text: string): void {
  pending.push(text);
  speech.speak();
}

beforeEach(() => {
  vi.clearAllMocks();
  pending.length = 0;
  restore = installBrowserSpeech("Chrome desktop");
  speech = new BrowserSpeech("en-US", "", 1, callbacks);
});
afterEach(() => {
  speech.close();
  restore();
  vi.useRealTimers();
});

describe("BrowserSpeech", () => {
  it("pulls one fragment per completed utterance and ignores pumps during playback", () => {
    speak("First.");
    speak("Second.");
    speak("Third.");
    expect(callbacks.nextSpeech).toHaveBeenCalledOnce();
    expect(fakeSynthesis.spoken.map((entry) => entry.text)).toEqual(["First."]);
    fakeSynthesis.complete();
    expect(fakeSynthesis.spoken.map((entry) => entry.text)).toEqual(["First.", "Second."]);
    fakeSynthesis.complete();
    expect(fakeSynthesis.spoken.map((entry) => entry.text)).toEqual(["First.", "Second.", "Third."]);
    fakeSynthesis.complete();
    expect(callbacks.speaking).toHaveBeenLastCalledWith(false);
  });

  it("prefers the browser default in the same language over an arbitrary exact regional voice", () => {
    const novelty = { name: "Odd voice", lang: "en-US", localService: true, default: false } as SpeechSynthesisVoice;
    const preferred = { name: "Preferred", lang: "en-CA", localService: true, default: true } as SpeechSynthesisVoice;
    fakeSynthesis.voices = [novelty, preferred];
    speak("Hello.");
    expect(fakeSynthesis.spoken[0]?.voice).toBe(preferred);
  });

  it("uses an explicitly selected voice even when another voice is the default", () => {
    const preferred = { voiceURI: "selected", name: "Selected", lang: "en-GB" } as SpeechSynthesisVoice;
    fakeSynthesis.voices = [{ voiceURI: "other", lang: "en-US", default: true } as SpeechSynthesisVoice, preferred];
    speech.close();
    speech = new BrowserSpeech("en-US", "selected", 1, callbacks);
    speak("Hello.");
    expect(fakeSynthesis.spoken[0]?.voice).toBe(preferred);
  });

  it("leaves selection to the browser when no default matches the language", () => {
    fakeSynthesis.voices = [{ lang: "en-US", localService: true, default: false } as SpeechSynthesisVoice];
    speak("Hello.");
    expect(fakeSynthesis.spoken[0]?.voice).toBeNull();
  });

  it("speaks a saved voice the browser no longer offers in the language with the default", () => {
    const fallback = { voiceURI: "default", lang: "en-US", default: true } as SpeechSynthesisVoice;
    for (const saved of ["french", "missing"]) {
      fakeSynthesis.spoken.length = 0;
      fakeSynthesis.voices = [{ voiceURI: "french", lang: "fr-FR" } as SpeechSynthesisVoice, fallback];
      speech.close();
      speech = new BrowserSpeech("en-US", saved, 1, callbacks);
      speak("Hello.");
      expect(fakeSynthesis.spoken[0]).toMatchObject({ text: "Hello.", voice: fallback });
    }
    expect(callbacks.error).not.toHaveBeenCalled();
  });

  it("waits for the saved voice to load on a fresh page and preserves queued fragments", () => {
    speech.close();
    speech = new BrowserSpeech("en-US", "selected", 1, callbacks);
    speak("First.");
    speak("Second.");
    expect(fakeSynthesis.spoken).toHaveLength(0);
    const selected = { voiceURI: "selected", lang: "en-US" } as SpeechSynthesisVoice;
    fakeSynthesis.voices = [selected];
    fakeSynthesis.dispatchEvent(new Event("voiceschanged"));
    expect(fakeSynthesis.spoken[0]).toMatchObject({ text: "First.", voice: selected });
    fakeSynthesis.complete();
    expect(fakeSynthesis.spoken[1]).toMatchObject({ text: "Second.", voice: selected });
  });

  it("speaks with the browser default when voices never load", () => {
    vi.useFakeTimers();
    speech.close();
    speech = new BrowserSpeech("en-US", "selected", 1, callbacks);
    speak("First.");
    expect(fakeSynthesis.spoken).toHaveLength(0);
    vi.advanceTimersByTime(5000);
    expect(fakeSynthesis.spoken.map((utterance) => utterance.text)).toEqual(["First."]);
    expect(fakeSynthesis.spoken[0]?.voice ?? null).toBeNull();
    expect(callbacks.error).not.toHaveBeenCalled();
  });

  it("cancels voice discovery when speech stops", () => {
    vi.useFakeTimers();
    speech.close();
    speech = new BrowserSpeech("en-US", "selected", 1, callbacks);
    speak("Cancelled.");
    speech.stopSpeaking();
    fakeSynthesis.voices = [{ voiceURI: "selected", lang: "en-US" } as SpeechSynthesisVoice];
    fakeSynthesis.dispatchEvent(new Event("voiceschanged"));
    vi.advanceTimersByTime(5000);
    expect(callbacks.error).not.toHaveBeenCalled();
    expect(fakeSynthesis.spoken).toHaveLength(0);
  });

  it("keeps discovery in charge when another fragment arrives before voiceschanged", () => {
    speech.close();
    speech = new BrowserSpeech("en-US", "selected", 1, callbacks);
    speak("First.");
    fakeSynthesis.voices = [{ voiceURI: "selected", lang: "en-US" } as SpeechSynthesisVoice];
    speak("Second.");
    expect(fakeSynthesis.spoken).toHaveLength(0);
    fakeSynthesis.dispatchEvent(new Event("voiceschanged"));
    expect(fakeSynthesis.spoken.map((utterance) => utterance.text)).toEqual(["First."]);
    fakeSynthesis.complete();
    expect(fakeSynthesis.spoken.map((utterance) => utterance.text)).toEqual(["First.", "Second."]);
    fakeSynthesis.complete();
    expect(callbacks.speaking).toHaveBeenLastCalledWith(false);
  });

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
    expect(callbacks.error).toHaveBeenCalledWith(expect.stringContaining("not-allowed"), true);
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
    speak("Hello.");
    expect(fakeSynthesis.spoken[0]?.lang).toBe("en-US");
    speech.close();
    fakeSynthesis.complete();
    expect(callbacks.speaking).not.toHaveBeenCalledWith(false);
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
    expect(browserRecognitionAvailable()).toBe(false);
    Object.defineProperty(navigator, "userAgent", { configurable: true, value: "iPhone Safari" });
    expect(browserRecognitionAvailable()).toBe(true);
    expect(browserSpeechAvoidsChimes()).toBe(true);
  });
});

// Tests incremental speech buffering, turn boundaries and cancellation.

import { describe, it } from "node:test";
import { expect } from "../tests/expect";
import { BrowserSpeechBuffer } from "./BrowserSpeechBuffer";

describe("BrowserSpeechBuffer", () => {
  it("reads appended text once and waits for a turn boundary before emitting its tail", () => {
    const buffer = new BrowserSpeechBuffer();
    buffer.append("**First.** Second. ");
    expect(buffer.next()).toBe("First.");
    expect(buffer.next()).toBe("Second.");
    expect(buffer.next()).toBeNull();
    buffer.append("Final tail");
    expect(buffer.next()).toBeNull();
    buffer.finish();
    expect(buffer.next()).toBe("Final tail");
    expect(buffer.next()).toBeNull();
  });

  it("keeps queued turn boundaries and parser state across input chunks", () => {
    const buffer = new BrowserSpeechBuffer();
    buffer.append("**Unclosed");
    buffer.finish();
    buffer.append("`Co");
    buffer.append("de`. ");
    buffer.append("Another reply");
    buffer.finish();
    expect(buffer.next()).toBe("**Unclosed");
    expect(buffer.next()).toBe("Code.");
    expect(buffer.next()).toBe("Another reply");
    expect(buffer.next()).toBeNull();
  });

  it("discards read-ahead fragments and parser state when playback is cancelled", () => {
    const buffer = new BrowserSpeechBuffer();
    buffer.append("Old. Queued. **unfinished");
    expect(buffer.next()).toBe("Old.");
    buffer.clear();
    buffer.append("Fresh reply");
    buffer.finish();
    expect(buffer.next()).toBe("Fresh reply");
    expect(buffer.next()).toBeNull();
  });

  it("drains many small transport chunks in order across queued turns", () => {
    const buffer = new BrowserSpeechBuffer();
    const sentences = Array.from({ length: 500 }, (_, index) => `Sentence ${index}.`);
    for (const sentence of sentences) {
      for (const char of sentence + " ") buffer.append(char);
    }
    buffer.finish();
    buffer.append("Final reply");
    buffer.finish();
    for (const sentence of sentences) expect(buffer.next()).toBe(sentence);
    expect(buffer.next()).toBe("Final reply");
    expect(buffer.next()).toBeNull();
  });
});

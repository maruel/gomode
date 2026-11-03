// Tests chunk-independent sentence cuts, decimal lookahead, bounded tokens and final tails.

import { describe, it } from "node:test";
import { expect } from "../tests/expect";
import { SentenceSegmenter } from "./SentenceSegmenter";

describe("SentenceSegmenter", () => {
  it("keeps decimals and closing punctuation together for every input split", () => {
    const text = 'Value 3.14. "Really?!" Next tail';
    for (let split = 0; split <= text.length; split++) {
      const segmenter = new SentenceSegmenter();
      const fragments: string[] = [];
      const emit = (text: string) => {
        fragments.push(text);
      };
      segmenter.push(text.slice(0, split), emit);
      segmenter.push(text.slice(split), emit);
      expect(fragments).toEqual(["Value 3.14.", '"Really?!"']);
      segmenter.finish(emit);
      expect(fragments).toEqual(["Value 3.14.", '"Really?!"', "Next tail"]);
      segmenter.finish(emit);
      expect(fragments).toHaveLength(3);
    }
  });

  it("bounds punctuation-free speech without splitting surrogate pairs", () => {
    const segmenter = new SentenceSegmenter();
    const fragments: string[] = [];
    const emit = (text: string) => {
      fragments.push(text);
    };
    const text = "a" + "😀".repeat(1000);
    for (let index = 0; index < text.length; index++) segmenter.push(text[index]!, emit);
    expect(fragments.length).toBeGreaterThan(1);
    segmenter.finish(emit);
    expect(fragments.join("")).toBe(text);
    expect(fragments.every((text) => text.length <= 181 && !/^[\uDC00-\uDFFF]|[\uD800-\uDBFF]$/.test(text))).toBe(true);
  });

  it("cuts long sentences at word boundaries and clears an interrupted tail", () => {
    const segmenter = new SentenceSegmenter();
    const fragments: string[] = [];
    const emit = (text: string) => {
      fragments.push(text);
    };
    segmenter.push("word ".repeat(100), emit);
    expect(fragments.length).toBeGreaterThan(1);
    expect(fragments.every((text) => /^(word )*word$/.test(text))).toBe(true);
    segmenter.clear();
    segmenter.push("Fresh tail", emit);
    segmenter.finish(emit);
    expect(fragments.at(-1)).toBe("Fresh tail");
  });
});

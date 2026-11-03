// Tests shared speech-filter goldens, chunk independence, bounded streaming, and turn resets.

import { readFileSync } from "node:fs";
import { it } from "node:test";
import { equal } from "node:assert/strict";
import { SpeechTextFilter } from "./SpeechTextFilter";

const cases = JSON.parse(
  readFileSync(new URL("../../testdata/speech-text/golden.json", import.meta.url), "utf8"),
) as Array<{
  name: string;
  input: string;
  expected: string;
}>;

for (const { name, input, expected } of cases) {
  it(`SpeechTextFilter ${name} is independent of chunk boundaries`, () => {
    for (let split = 0; split <= input.length; split++) {
      const filter = new SpeechTextFilter();
      equal(filter.push(input.slice(0, split)) + filter.push(input.slice(split)) + filter.finish(), expected);
    }
    const filter = new SpeechTextFilter();
    equal(Array.from(input, (c) => filter.push(c)).join("") + filter.finish(), expected);
    equal(filter.finish(), "");
    equal(filter.push("Fresh") + filter.finish(), "Fresh");
  });
}

it("SpeechTextFilter streams long lines and labels without waiting for completion", () => {
  for (const prefix of ["", "[", "```text\n"]) {
    const filter = new SpeechTextFilter();
    filter.push(prefix);
    for (let i = 0; i < 1000; i++) equal(filter.push("word "), "word ");
  }
  const filter = new SpeechTextFilter();
  filter.push("[label](");
  for (let i = 0; i < 1000; i++) equal(filter.push("destination"), "");
  equal(filter.push(") Next"), " Next");
});

it("SpeechTextFilter clears every partial parser state at interruption", () => {
  const filter = new SpeechTextFilter();
  for (const partial of [
    "#",
    "1.",
    "_",
    "`",
    "```kotlin",
    "[label]",
    "[label](hidden",
    "``code",
    "\\",
    "*unfinished",
    "*a `",
  ]) {
    filter.push(partial);
    filter.clear();
    equal(filter.push("**New**") + filter.finish(), "New");
  }
});

it("SpeechTextFilter bounds unmatched emphasis and resumes output before completion", () => {
  for (const marker of ["*", "_", "**", "__", "***", "___"]) {
    const filter = new SpeechTextFilter();
    equal(filter.push(marker + "a".repeat(252)), "");
    equal(filter.push("bbb"), marker + "a".repeat(252) + "bbb");
    for (let i = 0; i < 1000; i++) equal(filter.push("word "), "word ");
    equal(filter.finish(), "");
  }
});

it("SpeechTextFilter releases proven emphasis as soon as its closing boundary arrives", () => {
  for (const marker of ["*", "_", "**", "__", "***", "___"]) {
    const filter = new SpeechTextFilter();
    equal(filter.push("Before " + marker + "ready" + marker), "Before ");
    equal(filter.push(" now"), "ready now");
    equal(filter.finish(), "");
  }
});

// Removes the supported Markdown subset from streamed text for client-owned speech.

// Speech-only, not CommonMark: line headings/lists, emphasis, backticks, fenced
// code (without language labels), and inline link labels. Code content is literal.
// Inline code is line-local: newline or CR ends even an unmatched backtick span.
// Bracketed labels lose brackets even without a destination. No reference links,
// HTML or indented code parsing. Only prefix heading/list markers are recognized.
// ASCII punctuation escapes are literal outside code. Emphasis uses 1–3 matching
// asterisks/underscores, non-word outer boundaries and non-space inner boundaries.
// A pair cannot cross an enclosing label boundary or close within a nested label.
// Only proven pairs are removed; emitted speech is never retracted. Ambiguity is
// line-local and bounded: both delimiters plus closing lookahead must
// fit in 256 UTF-16 units; otherwise preserve the opening run literally. Thus long
// emphasis stays literal, intraword operators/identifiers survive, and _name_ is
// emphasis (escape it or use code for a literal). No whole reply/line is buffered.
// An unfinished destination is omitted through newline/end; nesting is capped at 32.
// Keep equivalent to Android SpeechTextFilter and testdata/speech-text/golden.json.
const EMPHASIS_LOOKAHEAD = 256;
const WORD = /^[\p{L}\p{N}\p{M}\uD800-\uDFFF]$/u;
const ASCII_PUNCTUATION = /^[\x21-\x2f\x3a-\x40\x5b-\x60\x7b-\x7e]$/;

export class SpeechTextFilter {
  private pending = "";
  private lineStart = true;
  private fence = "";
  private skipLine = false;
  private inlineCode = false;
  private label = false;
  private destinationDepth = 0;
  private previous = "";

  push(text: string): string {
    let output = "";
    for (let i = 0; i < text.length; i++) {
      this.pending += text.charAt(i);
      output += this.drain(false);
    }
    return output;
  }

  finish(): string {
    const output = this.drain(true);
    this.clear();
    return output;
  }

  clear(): void {
    this.pending = "";
    this.lineStart = true;
    this.fence = "";
    this.skipLine = false;
    this.inlineCode = false;
    this.label = false;
    this.destinationDepth = 0;
    this.previous = "";
  }

  // null waits for bounded lookahead; zero preserves a literal run.
  private emphasisEnd(count: number, final: boolean): number | null {
    const marker = this.pending.charAt(0);
    if (count > 3 || WORD.test(this.previous) || this.previous === marker) return 0;
    if (/\s/.test(this.pending[count] ?? "")) return 0;
    let code = false;
    let label = false;
    let destination = 0;
    for (let i = count; i < this.pending.length; i++) {
      const c = this.pending.charAt(i);
      if (c === "\n" || c === "\r") return 0;
      if (destination > 0) {
        if (c === "(") destination = Math.min(32, destination + 1);
        if (c === ")") destination--;
      } else if (c === "\\" && !code && ASCII_PUNCTUATION.test(this.pending[i + 1] ?? "")) {
        i++;
      } else if (c === "`") {
        const ticks = /^`{1,3}/.exec(this.pending.slice(i))?.[0] ?? "";
        i += ticks.length - 1;
        code = !code;
      } else if (!code && c === "[") label = true;
      else if (!code && c === "]") {
        if (label) {
          label = false;
          if (this.pending[i + 1] === "(") {
            destination = 1;
            i++;
          }
        } else if (this.label) return 0;
      } else if (!code && !label && c === marker) {
        let end = i;
        while (this.pending[end] === marker) end++;
        const run = end - i;
        if (run >= count && run <= 3 && !/\s/.test(this.pending.charAt(i - 1))) {
          if (end === this.pending.length && !final) break;
          if (end < EMPHASIS_LOOKAHEAD && !WORD.test(this.pending[end] ?? "")) return end - count;
        }
        i = end - 1;
      }
    }
    return final || this.pending.length >= EMPHASIS_LOOKAHEAD ? 0 : null;
  }

  private drain(final: boolean): string {
    let output = "";
    while (this.pending !== "") {
      const c = this.pending.charAt(0);
      let count = 1;
      let spoken = "";
      if (c === "\n" || c === "\r") {
        spoken = c;
        this.lineStart = true;
        this.skipLine = false;
        this.inlineCode = false;
        this.destinationDepth = 0;
        this.label = false;
      } else if (this.skipLine) {
        // Fence labels and closing fence lines are discarded incrementally.
      } else if (this.destinationDepth > 0) {
        if (c === "(") this.destinationDepth = Math.min(32, this.destinationDepth + 1);
        if (c === ")") this.destinationDepth--;
      } else {
        if (this.lineStart && !this.inlineCode) {
          if (c === " " || c === "\t") {
            output += c;
            this.pending = this.pending.slice(1);
            continue;
          }
          const fenceMarker = /^(?:`{3}|~{3})/.exec(this.pending)?.[0];
          if (fenceMarker && (this.fence === "" || this.fence === fenceMarker)) {
            this.fence = this.fence === "" ? fenceMarker : "";
            this.skipLine = true;
            this.pending = this.pending.slice(3);
            continue;
          }
          if (!final && /^(?:`{1,2}|~{1,2})$/.test(this.pending)) break;
          if (this.fence === "") {
            const marker = /^(?:#{1,6}|[-+*]|\d{1,9}[.)])[ \t]/.exec(this.pending)?.[0];
            if (marker) {
              this.pending = this.pending.slice(marker.length);
              this.lineStart = false;
              continue;
            }
            if (!final && /^(?:#{1,6}|[-+*]|\d{1,9}[.)]?)$/.test(this.pending)) break;
          }
          this.lineStart = false;
        }
        if (this.fence !== "") spoken = c;
        else if (c === "`") {
          const ticks = /^`{1,3}/.exec(this.pending)?.[0] ?? "";
          if (!final && ticks.length === this.pending.length && ticks.length < 3) break;
          count = ticks.length;
          this.inlineCode = !this.inlineCode;
        } else if (this.inlineCode) spoken = c;
        else if (c === "\\") {
          if (!final && this.pending.length === 1) break;
          if (ASCII_PUNCTUATION.test(this.pending[1] ?? "")) {
            count = 2;
            spoken = this.pending.charAt(1);
          } else spoken = c;
        } else if (c === "_" || c === "*") {
          const markers = /^(?:\*{1,4}|_{1,4})/.exec(this.pending)?.[0] ?? "";
          count = markers.length;
          if (!final && count === this.pending.length && count < 4) break;
          const end = this.emphasisEnd(count, final);
          if (end === null) break;
          if (end > 0) {
            // Finish the bounded inner slice before parsing adjacent raw tokens.
            // The closing delimiter also ends any inner unmatched emphasis candidate.
            const remaining = this.pending.slice(end + count);
            this.pending = this.pending.slice(count, end);
            output += this.drain(true);
            this.pending = remaining;
            this.previous = "";
            continue;
          }
          spoken = markers;
        } else if (c === "[") this.label = true;
        else if (c === "]" && this.label) {
          if (!final && this.pending.length === 1) break;
          this.label = false;
          if (this.pending[1] === "(") {
            count = 2;
            this.destinationDepth = 1;
          }
        } else spoken = c;
      }
      output += spoken;
      if (spoken !== "") this.previous = spoken.slice(-1);
      this.pending = this.pending.slice(count);
    }
    return output;
  }
}

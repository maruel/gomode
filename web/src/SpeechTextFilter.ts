// Removes the supported Markdown subset from streamed text for client-owned speech.

// Speech-only, not CommonMark: line headings/lists, emphasis, backticks, fenced
// code (without language labels), and inline link labels. Code content is literal.
// Inline code is line-local: newline or CR ends even an unmatched backtick span.
// Bracketed labels lose brackets even without a destination. No reference links,
// HTML, escapes, or indented code parsing. Only prefix heading/list markers are
// recognized. Outside code, asterisks and boundary underscores are formatting
// even when unpaired; word-internal underscore runs up to 8 characters are literal.
// Lookahead is at most 12 characters; labels/code/lines are never buffered.
// An unfinished destination is omitted through newline/end; nesting is capped at 32.
// Keep equivalent to Android SpeechTextFilter and testdata/speech-text/golden.json.
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
    for (const c of text) {
      this.pending += c;
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

  private drain(final: boolean): string {
    let output = "";
    while (this.pending !== "") {
      const c = this.pending[0]!;
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
          const ticks = /^`{1,3}/.exec(this.pending)![0];
          if (!final && ticks.length === this.pending.length && ticks.length < 3) break;
          count = ticks.length;
          this.inlineCode = !this.inlineCode;
        } else if (this.inlineCode) spoken = c;
        else if (c === "_") {
          const underscores = /^_{1,9}/.exec(this.pending)![0];
          if (!final && underscores.length === this.pending.length && underscores.length < 9) break;
          count = underscores.length;
          if (/^[\p{L}\p{N}]$/u.test(this.previous) && /^[\p{L}\p{N}]$/u.test(this.pending[count] ?? "")) {
            spoken = underscores;
          }
        } else if (c === "*") {
          // Emphasis delimiters do not contribute spoken characters.
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

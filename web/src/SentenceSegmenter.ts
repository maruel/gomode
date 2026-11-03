// Bounds streamed speech fragments while waiting for stable sentence punctuation.

// A sentence needs whitespace after punctuation and optional closers: a period
// at a chunk boundary may still belong to a decimal. Long sentences fall back
// to word boundaries (or a code-point boundary for an unbroken token).
const MAX_FRAGMENT_CHARS = 180;
const SENTENCE_END = /[.!?][.!?'")\]}»”’]*\s$/u;

export class SentenceSegmenter {
  private pending = "";

  push(text: string, emit: (text: string) => void): void {
    for (const char of text) {
      this.pending += char;
      if (SENTENCE_END.test(this.pending)) this.emit(this.pending.length, emit);
      else if (this.pending.length >= MAX_FRAGMENT_CHARS) {
        // A transport chunk can end between the two UTF-16 units of a pair.
        const cut = /[\uD800-\uDBFF]$/.test(this.pending) ? this.pending.length - 1 : this.pending.length;
        const space = this.pending.slice(0, cut).search(/\s+\S*$/u);
        this.emit(space > 0 ? space + 1 : cut, emit);
      }
    }
  }

  finish(emit: (text: string) => void): void {
    this.emit(this.pending.length, emit);
  }

  clear(): void {
    this.pending = "";
  }

  private emit(cut: number, emit: (text: string) => void): void {
    const text = this.pending.slice(0, cut).trim();
    this.pending = this.pending.slice(cut);
    if (text !== "") emit(text);
  }
}

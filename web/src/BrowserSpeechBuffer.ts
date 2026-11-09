// Buffers incoming text and pulls bounded speech fragments at playback speed.

import { SentenceSegmenter } from "./SentenceSegmenter";
import { SpeechTextFilter } from "./SpeechTextFilter";

export class BrowserSpeechBuffer {
  // Keep raw input chunks until read, independently of the visible transcript.
  // null ends a generated turn and flushes its parser state.
  private chunks: Array<string | null> = [];
  private head = 0;
  private offset = 0;
  private fragments: string[] = [];
  private readonly filter = new SpeechTextFilter();
  private readonly segmenter = new SentenceSegmenter();

  append(text: string): void {
    if (text !== "") this.chunks.push(text);
  }

  finish(): void {
    if (this.head === this.chunks.length || this.chunks.at(-1) !== null) this.chunks.push(null);
  }

  next(): string | null {
    while (this.fragments.length === 0) {
      const entry = this.chunks[this.head];
      if (entry === undefined) return null;
      if (entry === null) {
        this.advance();
        this.segmenter.push(this.filter.finish(), this.emit);
        this.segmenter.finish(this.emit);
        continue;
      }
      if (this.offset === entry.length) {
        this.advance();
        this.offset = 0;
        continue;
      }
      // Filter and segment only a small window, even for a single huge delta.
      const chunk = entry.slice(this.offset, this.offset + 256);
      this.offset += chunk.length;
      this.segmenter.push(this.filter.push(chunk), this.emit);
    }
    return this.fragments.shift() ?? null;
  }

  clear(): void {
    this.chunks = [];
    this.head = 0;
    this.offset = 0;
    this.fragments = [];
    this.filter.clear();
    this.segmenter.clear();
  }

  private readonly emit = (text: string): void => {
    this.fragments.push(text);
  };

  private advance(): void {
    // Release consumed input immediately and compact in batches to avoid
    // shifting a large backlog for every small transport chunk.
    this.chunks[this.head++] = null;
    if (this.head * 2 >= this.chunks.length) {
      this.chunks = this.chunks.slice(this.head);
      this.head = 0;
    }
  }
}

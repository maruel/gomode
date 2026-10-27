// Schedules linear audio gain ramps that preserve continuity when interrupted.

export class AudioGain {
  private from: number;
  private target: number;
  private start = 0;
  private end = 0;

  constructor(
    private readonly param: AudioParam,
    value: number,
  ) {
    this.from = value;
    this.target = value;
    param.value = value;
  }

  rampTo(target: number, duration: number, at: number): void {
    const progress = this.end > this.start ? Math.max(0, Math.min(1, (at - this.start) / (this.end - this.start))) : 1;
    this.from += (this.target - this.from) * progress;
    this.target = target;
    this.start = at;
    this.end = at + duration;
    this.param.cancelScheduledValues(at);
    this.param.setValueAtTime(this.from, at);
    this.param.linearRampToValueAtTime(target, this.end);
  }
}

// Tests continuity when a gain ramp is reversed or cancelled before its scheduled onset.

import { it } from "node:test";
import { expect, vi } from "../tests/expect";
import { AudioGain } from "./AudioGain";

it("reverses from the interpolated gain without jumping to the previous endpoint", () => {
  const param = { value: 1, cancelScheduledValues: vi.fn(), setValueAtTime: vi.fn(), linearRampToValueAtTime: vi.fn() };
  const gain = new AudioGain(param as unknown as AudioParam, 1);
  gain.rampTo(0.1, 0.03, 2);
  gain.rampTo(1, 0.1, 2.015);
  expect(param.cancelScheduledValues).toHaveBeenLastCalledWith(2.015);
  expect(param.setValueAtTime.mock.calls.at(-1)?.[0]).toBeCloseTo(0.55);
  expect(param.linearRampToValueAtTime).toHaveBeenLastCalledWith(1, 2.115);
});

it("cancels a future fade-in from silence", () => {
  const param = { value: 1, cancelScheduledValues: vi.fn(), setValueAtTime: vi.fn(), linearRampToValueAtTime: vi.fn() };
  const gain = new AudioGain(param as unknown as AudioParam, 0);
  gain.rampTo(1, 1.2, 2.2);
  gain.rampTo(0, 0.15, 2.1);
  expect(param.setValueAtTime).toHaveBeenLastCalledWith(0, 2.1);
  expect(param.linearRampToValueAtTime).toHaveBeenLastCalledWith(0, 2.25);
});

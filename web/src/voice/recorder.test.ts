import { expect, test } from "vitest";
import { downsample } from "./recorder";

test("an empty recording and silence have a visible waveform floor", () => {
  expect(downsample([], 4)).toEqual([0.08, 0.08, 0.08, 0.08]);
  expect(downsample([0, 0, 0, 0], 4)).toEqual([0.08, 0.08, 0.08, 0.08]);
});

test("averages buckets then normalizes to the loudest bucket", () => {
  expect(downsample([0, 0.25, 0.5, 1], 2)).toEqual([0.167, 1]);
});

test("short recordings still fill every requested bar", () => {
  expect(downsample([0.25, 1], 4)).toEqual([0.25, 0.25, 1, 1]);
});

test("rounds waveform samples and keeps very quiet bars visible", () => {
  expect(downsample([0, 0.001, 0.33333, 1], 4)).toEqual([0.08, 0.08, 0.333, 1]);
});

test("zero requested bars returns an empty waveform", () => {
  expect(downsample([1, 0.5], 0)).toEqual([]);
});

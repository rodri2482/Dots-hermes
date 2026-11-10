import { describe, expect, it } from "vitest";
import { downsample } from "./recorder";

describe("downsample", () => {
  it("returns a flat envelope for an empty recording", () => {
    expect(downsample([], 40)).toEqual(Array.from({ length: 40 }, () => 0.08));
  });

  it("returns exactly [bars] values within [0.08, 1]", () => {
    const values = Array.from({ length: 400 }, (_, i) => Math.abs(Math.sin(i / 10)));
    const out = downsample(values, 40);
    expect(out).toHaveLength(40);
    for (const v of out) {
      expect(v).toBeGreaterThanOrEqual(0.08);
      expect(v).toBeLessThanOrEqual(1);
    }
  });

  it("normalizes the packets on their peak", () => {
    // Une barre par paquet : [0.5, 1, 0.25] tel quel, ramené à une crête de 1.
    const out = downsample([0.5, 1, 0.25], 3);
    expect(out[1]).toBe(1);
    expect(out[0]).toBe(0.5);
    expect(out[2]).toBe(0.25);
  });

  it("keeps a floor of 0.08 for a silent recording", () => {
    expect(downsample(new Array(100).fill(0), 40).every((v) => v === 0.08)).toBe(true);
  });

  it("averages each packet instead of sampling one point", () => {
    // 4 valeurs, 2 barres : [0,1] puis [1,0] → deux paquets de crête égale. Un échantillonnage du
    // premier point donnerait [0.08, 1] après normalisation ; la moyenne donne [1, 1].
    const out = downsample([0, 1, 1, 0], 2);
    expect(out[0]).toBe(1);
    expect(out[1]).toBe(1);
  });
});

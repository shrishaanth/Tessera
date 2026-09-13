import { describe, expect, it } from "vitest";

import { formatProbability, riskColour, riskRadius, timeAgo } from "../components/risk";

describe("risk display helpers", () => {
  it("gives every band its own colour", () => {
    const colours = new Set([
      riskColour("LOW"),
      riskColour("MODERATE"),
      riskColour("HIGH"),
      riskColour("SEVERE"),
    ]);
    // Four bands that shared a colour would be four bands the operator cannot tell
    // apart, which is the same as not having them.
    expect(colours.size).toBe(4);
  });

  it("grows the marker with risk, so severity survives greyscale", () => {
    expect(riskRadius("SEVERE")).toBeGreaterThan(riskRadius("HIGH"));
    expect(riskRadius("HIGH")).toBeGreaterThan(riskRadius("MODERATE"));
    expect(riskRadius("MODERATE")).toBeGreaterThan(riskRadius("LOW"));
  });

  describe("formatProbability", () => {
    it("renders a real probability as a percentage", () => {
      expect(formatProbability(0.715, true)).toBe("72%");
      expect(formatProbability(0.0, true)).toBe("0%");
    });

    it("renders an absent prediction as a dash, never as zero", () => {
      // A probability of 0 means the model is confident nothing will happen.
      // Absence means no model has run. Showing the second as the first would be
      // inventing a prediction nothing made.
      expect(formatProbability(null, false)).toBe("—");
      expect(formatProbability(undefined as unknown as null, false)).toBe("—");
      // Even a present number is suppressed when the row says it was not scored,
      // so the two sources of truth cannot disagree on screen.
      expect(formatProbability(0.5, false)).toBe("—");
    });
  });

  describe("timeAgo", () => {
    const now = 1_767_225_600_000;

    it("reads as elapsed time, not a clock", () => {
      expect(timeAgo(now - 3_000, now)).toBe("just now");
      expect(timeAgo(now - 42_000, now)).toBe("42s ago");
      expect(timeAgo(now - 5 * 60_000, now)).toBe("5m ago");
      expect(timeAgo(now - 3 * 3_600_000, now)).toBe("3h ago");
      expect(timeAgo(now - 2 * 86_400_000, now)).toBe("2d ago");
    });

    it("does not render a future timestamp as negative", () => {
      // Clock skew between the browser and the service is ordinary; "-4s ago" is
      // not something to show an operator.
      expect(timeAgo(now + 4_000, now)).toBe("just now");
    });
  });
});

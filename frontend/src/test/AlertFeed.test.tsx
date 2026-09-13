import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { AlertFeed } from "../components/AlertFeed";
import type { RiskAlert } from "../api/types";

function alert(overrides: Partial<RiskAlert> = {}): RiskAlert {
  return {
    vehicleId: "VEH-010",
    driverId: "DRV-010",
    riskTier: "RISKY",
    windowStart: 1_767_225_600_000,
    windowEnd: 1_767_225_900_000,
    riskScore: 100,
    hardBrakeCount: 14,
    speedViolationCount: 1,
    incidentCount: 0,
    readingCount: 62,
    reason: "Risk score 100 from 14 hard brakes and 1 speed violation in 62 readings",
    raisedAt: Date.now(),
    ...overrides,
  };
}

describe("AlertFeed", () => {
  it("distinguishes a calm fleet from a dead feed", () => {
    // Both are an empty list, which is why the connection state is shown at all:
    // otherwise an operator cannot tell "nothing is wrong" from "we would not know
    // if it were".
    const { rerender } = render(
      <AlertFeed alerts={[]} connected onSelect={vi.fn()} retries={0} />,
    );
    expect(screen.getByText(/feed is connected/i)).toBeInTheDocument();

    rerender(<AlertFeed alerts={[]} connected={false} onSelect={vi.fn()} retries={0} />);
    expect(screen.getByText(/not connected/i)).toBeInTheDocument();
  });

  it("reports reconnection attempts rather than just going quiet", () => {
    render(<AlertFeed alerts={[]} connected={false} onSelect={vi.fn()} retries={3} />);
    expect(screen.getByText(/reconnecting \(3\)/i)).toBeInTheDocument();
  });

  it("shows the reason, including the sample size behind the score", () => {
    render(<AlertFeed alerts={[alert()]} connected onSelect={vi.fn()} retries={0} />);
    // The score is a rate, so "14 hard brakes" means something different over 62
    // readings than over 300 — the denominator travels with it.
    expect(screen.getByText(/62 readings/)).toBeInTheDocument();
  });

  it("marks an incident out from a threshold crossing", () => {
    const { container } = render(
      <AlertFeed alerts={[alert({ incidentCount: 2 })]} connected onSelect={vi.fn()} retries={0} />,
    );
    // An incident is the outcome the whole system exists to surface; rendering it
    // identically to a score threshold would bury it.
    expect(container.querySelector(".alert--incident")).not.toBeNull();
  });
});

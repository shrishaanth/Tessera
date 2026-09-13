import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { FleetTable } from "../components/FleetTable";
import type { VehicleRisk } from "../api/types";

function vehicle(overrides: Partial<VehicleRisk>): VehicleRisk {
  return {
    vehicleId: "VEH-001",
    driverId: "DRV-001",
    driverName: "A. Okafor",
    riskTier: "SAFE",
    windowStart: 1_767_225_600_000,
    windowEnd: 1_767_225_900_000,
    readingCount: 300,
    hardBrakeCount: 4,
    speedViolationCount: 0,
    incidentCount: 0,
    avgSpeedKph: 30,
    maxSpeedKph: 45,
    avgOverLimitRatio: 0.7,
    maxSeverity: 0,
    movingRatio: 1,
    lat: 42.36,
    lon: -71.06,
    headingDeg: 90,
    lastReadingTs: 1_767_225_899_000,
    riskScore: 20,
    riskLevel: "LOW",
    predictedLabel: null,
    probability: null,
    modelScored: false,
    ...overrides,
  };
}

describe("FleetTable", () => {
  it("puts the riskiest vehicles first, whatever order they arrive in", () => {
    render(
      <FleetTable
        vehicles={[
          vehicle({ vehicleId: "VEH-001", riskScore: 20 }),
          vehicle({ vehicleId: "VEH-002", riskScore: 90, riskLevel: "SEVERE" }),
          vehicle({ vehicleId: "VEH-003", riskScore: 55, riskLevel: "MODERATE" }),
        ]}
        selectedId={null}
        onSelect={vi.fn()}
      />,
    );

    // On a map the riskiest vehicle is wherever it happens to be; here it is at
    // the top, which is the reason the list exists beside the map.
    const rows = screen.getAllByRole("button");
    expect(within(rows[0]).getByText("VEH-002")).toBeInTheDocument();
    expect(within(rows[1]).getByText("VEH-003")).toBeInTheDocument();
    expect(within(rows[2]).getByText("VEH-001")).toBeInTheDocument();
  });

  it("shows a dash rather than 0% when no model has run", () => {
    render(
      <FleetTable
        vehicles={[vehicle({ probability: null, modelScored: false })]}
        selectedId={null}
        onSelect={vi.fn()}
      />,
    );
    expect(screen.getByText("—")).toBeInTheDocument();
    expect(screen.queryByText("0%")).not.toBeInTheDocument();
  });

  it("shows a real probability once a model has scored", () => {
    render(
      <FleetTable
        vehicles={[vehicle({ probability: 0.715, predictedLabel: 1, modelScored: true })]}
        selectedId={null}
        onSelect={vi.fn()}
      />,
    );
    expect(screen.getByText("72%")).toBeInTheDocument();
  });

  it("selects a vehicle by click and by keyboard", async () => {
    const onSelect = vi.fn();
    render(
      <FleetTable
        vehicles={[vehicle({ vehicleId: "VEH-007" })]}
        selectedId={null}
        onSelect={onSelect}
      />,
    );

    await userEvent.click(screen.getByRole("button"));
    expect(onSelect).toHaveBeenCalledWith("VEH-007");

    // The rows are the primary way to pick a vehicle, so they must not be
    // mouse-only.
    onSelect.mockClear();
    screen.getByRole("button").focus();
    await userEvent.keyboard("{Enter}");
    expect(onSelect).toHaveBeenCalledWith("VEH-007");
  });

  it("says what to do when nothing has reported", () => {
    render(<FleetTable vehicles={[]} selectedId={null} onSelect={vi.fn()} />);
    // An empty table with no explanation reads as a broken dashboard.
    expect(screen.getByText(/no vehicles have reported/i)).toBeInTheDocument();
  });
});

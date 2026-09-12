import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { StatusFilterBar } from "../components/StatusFilterBar";
import type { Vehicle, VehicleStatus } from "../api/types";

const v = (id: string, status: VehicleStatus): Vehicle => ({
  vehicleId: id,
  driverName: "d",
  status,
  latitude: 42.36,
  longitude: -71.06,
  headingDeg: 0,
  speedKph: 10,
  lastReportEpochMs: Date.now(),
});

describe("StatusFilterBar", () => {
  const vehicles = [v("1", "ACTIVE"), v("2", "ACTIVE"), v("3", "ON_SITE"), v("4", "OFFLINE")];

  it("shows a chip per status with counts", () => {
    render(
      <StatusFilterBar
        active={new Set<VehicleStatus>(["ACTIVE", "ON_SITE", "OFFLINE"])}
        onToggle={() => {}}
        vehicles={vehicles}
      />,
    );
    expect(screen.getByRole("button", { name: /Active 2/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /On site 1/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Offline 1/ })).toBeInTheDocument();
  });

  it("reports aria-pressed and fires onToggle", async () => {
    const onToggle = vi.fn();
    render(
      <StatusFilterBar
        active={new Set<VehicleStatus>(["ACTIVE"])}
        onToggle={onToggle}
        vehicles={vehicles}
      />,
    );
    const onSite = screen.getByRole("button", { name: /On site/ });
    expect(onSite).toHaveAttribute("aria-pressed", "false");
    await userEvent.click(onSite);
    expect(onToggle).toHaveBeenCalledWith("ON_SITE");
  });
});

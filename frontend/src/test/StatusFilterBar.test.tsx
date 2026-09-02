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
  currentJobId: null,
});

describe("StatusFilterBar", () => {
  const vehicles = [v("1", "AVAILABLE"), v("2", "AVAILABLE"), v("3", "EN_ROUTE"), v("4", "OFFLINE")];

  it("shows a chip per status with counts", () => {
    render(<StatusFilterBar active={new Set(["AVAILABLE", "EN_ROUTE", "ON_SITE", "OFFLINE"])} onToggle={() => {}} vehicles={vehicles} />);
    expect(screen.getByRole("button", { name: /Available 2/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /En route 1/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /On site 0/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Offline 1/ })).toBeInTheDocument();
  });

  it("reports aria-pressed and fires onToggle", async () => {
    const onToggle = vi.fn();
    render(<StatusFilterBar active={new Set(["AVAILABLE"])} onToggle={onToggle} vehicles={vehicles} />);
    const enRoute = screen.getByRole("button", { name: /En route/ });
    expect(enRoute).toHaveAttribute("aria-pressed", "false");
    await userEvent.click(enRoute);
    expect(onToggle).toHaveBeenCalledWith("EN_ROUTE");
  });
});

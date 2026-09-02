import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Alert } from "../api/types";
import { AlertsView } from "../components/AlertsView";

const ackAlert = vi.fn();
let alerts: Alert[] = [];

vi.mock("../live/LiveStreamContext", () => ({
  useLiveStream: () => ({
    vehicles: [],
    connected: true,
    lastUpdateMs: null,
    geofenceFeed: [],
    alerts,
    unacknowledged: alerts.filter((a) => !a.acknowledged).length,
    ackAlert,
  }),
}));

const alert = (id: string, acknowledged = false): Alert => ({
  id,
  type: "DWELL_EXCEEDED",
  severity: "WARNING",
  vehicleId: "TRUCK-12",
  siteId: "SITE-A",
  message: `${id}: TRUCK-12 has been on site "Acme Corp" for 35 min`,
  createdAtEpochMs: Date.now() - Number(id.slice(-1)) * 1000,
  acknowledged,
});

describe("AlertsView", () => {
  beforeEach(() => {
    ackAlert.mockReset();
  });

  it("lists unacknowledged alerts and hides acknowledged ones by default", () => {
    alerts = [alert("ALERT-1"), alert("ALERT-2", true)];
    render(<AlertsView />);
    expect(screen.getByText(/ALERT-1:/)).toBeInTheDocument();
    expect(screen.queryByText(/ALERT-2:/)).not.toBeInTheDocument();
  });

  it("acknowledges an alert on click", async () => {
    alerts = [alert("ALERT-9")];
    ackAlert.mockResolvedValue(undefined);
    render(<AlertsView />);
    await userEvent.click(screen.getByRole("button", { name: /acknowledge/i }));
    expect(ackAlert).toHaveBeenCalledWith("ALERT-9");
  });

  it("shows an empty state when there are no alerts", () => {
    alerts = [];
    render(<AlertsView />);
    expect(screen.getByText("No alerts.")).toBeInTheDocument();
  });
});

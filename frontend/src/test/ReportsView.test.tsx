import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ReportsView } from "../components/ReportsView";
import { api } from "../api/client";
import type { DwellReport, OnTimeReport, Readiness, ReportFilterOptions } from "../api/types";

vi.mock("../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/client")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      reportReadiness: vi.fn(),
      reportFilters: vi.fn(),
      onTimeReport: vi.fn(),
      dwellReport: vi.fn(),
    },
  };
});

const readiness = (ready: boolean, synthetic = false): Readiness => ({
  ready,
  collectionDays: ready ? 20 : 3,
  minCollectionDays: 14,
  completedJobs: ready ? 80 : 5,
  minCompletedJobs: 50,
  siteExits: 40,
  minSiteExits: 20,
  reasons: ready ? [] : ["Only 3 of 14 days of history collected"],
  syntheticHistory: synthetic,
});

const filters: ReportFilterOptions = {
  routes: ["North Loop"],
  drivers: ["Ada"],
  sites: [{ id: "S1", name: "Acme Corp" }],
};

const onTime: OnTimeReport = {
  fromEpochMs: 0,
  toEpochMs: 1,
  completed: 40,
  onTime: 34,
  onTimePct: 85,
  byWeek: [
    { weekStartEpochMs: 1704067200000, completed: 20, onTime: 15, onTimePct: 75 },
    { weekStartEpochMs: 1704672000000, completed: 20, onTime: 19, onTimePct: 95 },
  ],
  trend: { previousValue: 80, deltaValue: 5, direction: "up" },
  provisional: false,
};

const dwell: DwellReport = {
  fromEpochMs: 0,
  toEpochMs: 1,
  totalVisits: 50,
  overallAvgDwellSeconds: 1200,
  bySite: [
    { siteId: "S1", siteName: "Acme Corp", visits: 30, avgDwellSeconds: 1500, enoughData: true },
    { siteId: "S2", siteName: "North Yard", visits: 5, avgDwellSeconds: 600, enoughData: false },
  ],
  trend: { previousValue: 1300, deltaValue: -100, direction: "down" },
  provisional: false,
};

describe("ReportsView", () => {
  beforeEach(() => {
    (api.reportFilters as ReturnType<typeof vi.fn>).mockResolvedValue(filters);
    (api.onTimeReport as ReturnType<typeof vi.fn>).mockResolvedValue(onTime);
    (api.dwellReport as ReturnType<typeof vi.fn>).mockResolvedValue(dwell);
  });

  it("shows the headline KPIs and a good trend for on-time", async () => {
    (api.reportReadiness as ReturnType<typeof vi.fn>).mockResolvedValue(readiness(true));
    render(<ReportsView />);
    expect(await screen.findByText("85.0%")).toBeInTheDocument();
    expect(screen.getByText("20 min")).toBeInTheDocument(); // 1200s
    expect(screen.getByText(/▲ 5.0 pts vs previous period/)).toBeInTheDocument();
  });

  it("renders the FR-4.4 not-reliable banner with reasons when readiness is false", async () => {
    (api.reportReadiness as ReturnType<typeof vi.fn>).mockResolvedValue(readiness(false));
    render(<ReportsView />);
    expect(await screen.findByText(/not yet reliable/i)).toBeInTheDocument();
    expect(screen.getByText(/Only 3 of 14 days/)).toBeInTheDocument();
  });

  it("flags synthetic demo history", async () => {
    (api.reportReadiness as ReturnType<typeof vi.fn>).mockResolvedValue(readiness(true, true));
    render(<ReportsView />);
    expect(await screen.findByText(/synthetic back-filled data/i)).toBeInTheDocument();
  });

  it("renders the weekly bar list and the per-site dwell table", async () => {
    (api.reportReadiness as ReturnType<typeof vi.fn>).mockResolvedValue(readiness(true));
    render(<ReportsView />);
    await screen.findByText("85.0%");
    // weekly bars: two rows, values 75% and 95%
    expect(screen.getByText("75.0%")).toBeInTheDocument();
    expect(screen.getByText("95.0%")).toBeInTheDocument();
    // dwell table ("Acme Corp" also appears in the site filter <option>)
    expect(screen.getAllByText("Acme Corp").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("North Yard")).toBeInTheDocument();
    expect(screen.getByText("insufficient data")).toBeInTheDocument();
  });

  it("refetches when a filter changes", async () => {
    (api.reportReadiness as ReturnType<typeof vi.fn>).mockResolvedValue(readiness(true));
    render(<ReportsView />);
    await screen.findByText("85.0%");
    const callsBefore = (api.onTimeReport as ReturnType<typeof vi.fn>).mock.calls.length;
    screen.getByRole("button", { name: "7d" }).click();
    await waitFor(() =>
      expect((api.onTimeReport as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(
        callsBefore,
      ),
    );
  });
});

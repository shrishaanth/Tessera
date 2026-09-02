import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { NewJobPanel } from "../components/NewJobPanel";
import type { NearestVehicle } from "../api/types";
import { api } from "../api/client";

vi.mock("../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/client")>();
  return { ...actual, api: { ...actual.api, assignJob: vi.fn() } };
});

const shortlist: NearestVehicle[] = [
  { vehicleId: "SIM-002", driverName: "B. Nguyen", straightLineMeters: 420, travelSeconds: 95, latitude: 42.36, longitude: -71.06 },
  { vehicleId: "SIM-014", driverName: "N. Dubois", straightLineMeters: 1300, travelSeconds: 260, latitude: 42.36, longitude: -71.07 },
];

describe("NewJobPanel", () => {
  beforeEach(() => vi.clearAllMocks());

  it("prompts to pick a location before a draft exists", async () => {
    const onStart = vi.fn();
    render(
      <NewJobPanel jobDraft={null} jobId={null} shortlist={[]} onStart={onStart} onCancel={() => {}} onAssigned={() => {}} />,
    );
    await userEvent.click(screen.getByRole("button", { name: /pick location/i }));
    expect(onStart).toHaveBeenCalled();
  });

  it("renders the ranked shortlist with ETAs in order", () => {
    render(
      <NewJobPanel
        jobDraft={{ lat: 42.3601, lon: -71.0589 }}
        jobId="JOB-1"
        shortlist={shortlist}
        onStart={() => {}}
        onCancel={() => {}}
        onAssigned={() => {}}
      />,
    );
    expect(screen.getByText(/1\. SIM-002/)).toBeInTheDocument();
    expect(screen.getByText(/2\. SIM-014/)).toBeInTheDocument();
    expect(screen.getByText("2 min")).toBeInTheDocument(); // 95s -> 2 min
    expect(screen.getByText("4 min")).toBeInTheDocument(); // 260s -> 4 min
  });

  it("assigns a job in one click and reports completion", async () => {
    (api.assignJob as ReturnType<typeof vi.fn>).mockResolvedValue({});
    const onAssigned = vi.fn();
    render(
      <NewJobPanel
        jobDraft={{ lat: 42.3601, lon: -71.0589 }}
        jobId="JOB-1"
        shortlist={shortlist}
        onStart={() => {}}
        onCancel={() => {}}
        onAssigned={onAssigned}
      />,
    );
    await userEvent.click(screen.getAllByRole("button", { name: "Assign" })[0]);
    expect(api.assignJob).toHaveBeenCalledWith("JOB-1", "SIM-002");
    expect(onAssigned).toHaveBeenCalled();
  });
});

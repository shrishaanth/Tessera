import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AddressSearchBox } from "../components/AddressSearchBox";
import { api } from "../api/client";
import type { GeocodeResponse, SiteView } from "../api/types";

vi.mock("../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/client")>();
  return { ...actual, api: { ...actual.api, geocode: vi.fn(), searchSites: vi.fn() } };
});

const geo = (): GeocodeResponse => ({
  query: "boston common",
  degraded: false,
  results: [
    {
      displayName: "Boston Common, Boston, MA, USA",
      latitude: 42.3554,
      longitude: -71.0633,
      category: "leisure",
      type: "park",
      importance: 0.6,
    },
  ],
});

const site = (): SiteView => ({
  id: "S1",
  name: "Common Depot",
  address: "Tremont St",
  kind: "RADIUS",
  outline: [[42.3556, -71.0633]],
  centerLat: 42.3556,
  centerLon: -71.0633,
  radiusMeters: 140,
  dwellAlertSeconds: null,
  createdAtEpochMs: 0,
});

describe("AddressSearchBox", () => {
  beforeEach(() => {
    (api.geocode as ReturnType<typeof vi.fn>).mockResolvedValue(geo());
    (api.searchSites as ReturnType<typeof vi.fn>).mockResolvedValue([site()]);
  });

  it("does not query for fewer than 3 characters", async () => {
    render(<AddressSearchBox onPick={() => {}} />);
    await userEvent.type(screen.getByRole("textbox"), "bo");
    expect(api.geocode).not.toHaveBeenCalled();
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("shows address and customer-site groups after a debounced query", async () => {
    render(<AddressSearchBox onPick={() => {}} />);
    await userEvent.type(screen.getByRole("textbox"), "boston common");

    await waitFor(() => expect(api.geocode).toHaveBeenCalledWith("boston common"));
    expect(await screen.findByText("Customer sites")).toBeInTheDocument();
    expect(screen.getByText("Addresses")).toBeInTheDocument();
    expect(screen.getByText(/Boston Common, Boston/)).toBeInTheDocument();
    expect(screen.getByText(/Common Depot/)).toBeInTheDocument();
  });

  it("returns the picked location's real coordinates", async () => {
    const onPick = vi.fn();
    render(<AddressSearchBox onPick={onPick} />);
    await userEvent.type(screen.getByRole("textbox"), "boston common");

    const option = await screen.findByText(/Boston Common, Boston/);
    await userEvent.click(option);
    expect(onPick).toHaveBeenCalledWith(
      expect.objectContaining({ lat: 42.3554, lon: -71.0633, kind: "address" }),
    );
  });
});

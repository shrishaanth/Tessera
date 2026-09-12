import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, STATUS_COLOR } from "../api/client";

function mockFetch(status: number, body: unknown) {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    statusText: "x",
    json: async () => body,
  } as Response);
}

describe("api client", () => {
  afterEach(() => vi.restoreAllMocks());

  it("sends credentials and parses JSON on success", async () => {
    const f = mockFetch(200, [{ vehicleId: "A" }]);
    vi.stubGlobal("fetch", f);
    const res = await api.vehicles();
    expect(res).toEqual([{ vehicleId: "A" }]);
    expect(f).toHaveBeenCalledWith("/api/vehicles", expect.objectContaining({ credentials: "include" }));
  });

  it("throws ApiError with status and server message on failure", async () => {
    vi.stubGlobal("fetch", mockFetch(409, { message: "Site name is required" }));
    await expect(api.createSite({ name: "" })).rejects.toMatchObject({
      status: 409,
      message: "Site name is required",
    });
    await expect(api.createSite({ name: "" })).rejects.toBeInstanceOf(ApiError);
  });

  it("builds the dwell report query string", async () => {
    const f = mockFetch(200, {});
    vi.stubGlobal("fetch", f);
    await api.dwellReport({ from: 100, to: 200, siteId: "S1" });
    expect(f).toHaveBeenCalledWith("/api/reports/dwell?from=100&to=200&siteId=S1", expect.anything());
  });

  it("maps every status to a colour", () => {
    expect(STATUS_COLOR.ACTIVE).toMatch(/^#/);
    expect(STATUS_COLOR.ON_SITE).toMatch(/^#/);
    expect(STATUS_COLOR.OFFLINE).toMatch(/^#/);
  });
});

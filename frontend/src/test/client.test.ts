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
    vi.stubGlobal("fetch", mockFetch(409, { message: "Job already assigned" }));
    await expect(api.assignJob("JOB-1", "V-1")).rejects.toMatchObject({
      status: 409,
      message: "Job already assigned",
    });
    await expect(api.assignJob("JOB-1", "V-1")).rejects.toBeInstanceOf(ApiError);
  });

  it("builds the nearest query string", async () => {
    const f = mockFetch(200, []);
    vi.stubGlobal("fetch", f);
    await api.nearest(42.36, -71.06, 3);
    expect(f).toHaveBeenCalledWith("/api/vehicles/nearest?lat=42.36&lon=-71.06&limit=3", expect.anything());
  });

  it("maps every status to a colour", () => {
    expect(STATUS_COLOR.AVAILABLE).toMatch(/^#/);
    expect(STATUS_COLOR.EN_ROUTE).toMatch(/^#/);
    expect(STATUS_COLOR.ON_SITE).toMatch(/^#/);
    expect(STATUS_COLOR.OFFLINE).toMatch(/^#/);
  });
});

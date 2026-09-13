import type {
  AlertStatus,
  DataSourceInfo,
  FleetSnapshot,
  RiskAlert,
  SegmentRisk,
  VehicleRisk,
} from "./types";

/**
 * Thin wrappers over the reporting API.
 *
 * <p>Relative URLs throughout. In development Vite proxies `/api` and `/ws` to the
 * service, and in a build the service serves this bundle itself — so the browser
 * always talks to one origin and there is no CORS configuration, no base URL to
 * configure, and nothing to get wrong between environments.
 */

/** A failed request, carrying the status so callers can tell 404 from 503. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(path, {
    signal,
    headers: { Accept: "application/json" },
  });
  if (!response.ok) {
    throw new ApiError(
      response.status,
      `${path} responded ${response.status} ${response.statusText}`,
    );
  }
  return (await response.json()) as T;
}

export const api = {
  fleet: (signal?: AbortSignal) => get<FleetSnapshot>("/api/fleet", signal),

  vehicle: (vehicleId: string, signal?: AbortSignal) =>
    get<VehicleRisk>(`/api/vehicles/${encodeURIComponent(vehicleId)}`, signal),

  vehicleHistory: (vehicleId: string, windows = 30, signal?: AbortSignal) =>
    get<VehicleRisk[]>(
      `/api/vehicles/${encodeURIComponent(vehicleId)}/history?windows=${windows}`,
      signal,
    ),

  segmentHistory: (segmentId: string, windows = 30, signal?: AbortSignal) =>
    get<SegmentRisk[]>(
      `/api/segments/${encodeURIComponent(segmentId)}/history?windows=${windows}`,
      signal,
    ),

  alerts: (limit = 50, signal?: AbortSignal) =>
    get<RiskAlert[]>(`/api/alerts?limit=${limit}`, signal),

  alertStatus: (signal?: AbortSignal) =>
    get<AlertStatus>("/api/alerts/status", signal),

  dataSource: (signal?: AbortSignal) =>
    get<DataSourceInfo>("/api/meta/data-source", signal),
};

/**
 * The WebSocket URL for the alert feed.
 *
 * <p>Derived from the page's own origin rather than configured, so it follows
 * whatever host and scheme the app was loaded from. Hard-coding `ws://localhost`
 * works right up until the page is opened from anywhere else.
 */
export function alertSocketUrl(): string {
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${window.location.host}/ws/alerts`;
}

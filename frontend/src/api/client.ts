import type {
  Alert,
  CreateJobResponse,
  DataSourceInfo,
  DwellReport,
  GeofenceEventRecord,
  Identity,
  Job,
  NearestVehicle,
  OnTimeReport,
  Readiness,
  ReportFilterOptions,
  SiteDefinition,
  SiteView,
  Vehicle,
  VehicleDetail,
  VehicleStatus,
} from "./types";

export interface ReportQuery {
  from?: number;
  to?: number;
  route?: string;
  driver?: string;
  siteId?: string;
}

/** Thrown for any non-2xx response; carries the HTTP status. */
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    ...init,
  });
  if (!res.ok) {
    let msg = res.statusText;
    try {
      const body = await res.json();
      if (body?.message) msg = body.message;
    } catch {
      /* no body */
    }
    throw new ApiError(res.status, msg);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const api = {
  me: () => req<Identity>("/api/auth/me"),

  login: (username: string, password: string) =>
    req<Identity>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    }),

  logout: () => req<void>("/api/auth/logout", { method: "POST" }),

  vehicles: (status?: VehicleStatus) =>
    req<Vehicle[]>("/api/vehicles" + (status ? `?status=${status}` : "")),

  vehicleDetail: (id: string) => req<VehicleDetail>(`/api/vehicles/${encodeURIComponent(id)}`),

  nearest: (lat: number, lon: number, limit = 5) =>
    req<NearestVehicle[]>(`/api/vehicles/nearest?lat=${lat}&lon=${lon}&limit=${limit}`),

  createJob: (destLatitude: number, destLongitude: number, destinationAddress?: string) =>
    req<CreateJobResponse>("/api/jobs", {
      method: "POST",
      body: JSON.stringify({ destLatitude, destLongitude, destinationAddress: destinationAddress ?? null }),
    }),

  assignJob: (jobId: string, vehicleId: string) =>
    req<Job>(`/api/jobs/${encodeURIComponent(jobId)}/assign`, {
      method: "POST",
      body: JSON.stringify({ vehicleId }),
    }),

  dataSources: () => req<DataSourceInfo[]>("/api/data-sources"),

  sites: () => req<SiteView[]>("/api/sites"),

  createSite: (def: SiteDefinition) =>
    req<SiteView>("/api/sites", { method: "POST", body: JSON.stringify(def) }),

  deleteSite: (id: string) =>
    req<void>(`/api/sites/${encodeURIComponent(id)}`, { method: "DELETE" }),

  alerts: (includeAcknowledged = false) =>
    req<Alert[]>(`/api/alerts?includeAcknowledged=${includeAcknowledged}`),

  ackAlert: (id: string) =>
    req<Alert>(`/api/alerts/${encodeURIComponent(id)}/ack`, { method: "POST" }),

  geofenceEvents: (params: { vehicleId?: string; siteId?: string; limit?: number } = {}) => {
    const q = new URLSearchParams();
    if (params.vehicleId) q.set("vehicleId", params.vehicleId);
    if (params.siteId) q.set("siteId", params.siteId);
    q.set("limit", String(params.limit ?? 100));
    return req<GeofenceEventRecord[]>(`/api/geofence-events?${q.toString()}`);
  },

  reportReadiness: () => req<Readiness>("/api/reports/readiness"),

  reportFilters: () => req<ReportFilterOptions>("/api/reports/filters"),

  onTimeReport: (q: ReportQuery = {}) =>
    req<OnTimeReport>(`/api/reports/on-time${reportQs(q)}`),

  dwellReport: (q: ReportQuery = {}) => req<DwellReport>(`/api/reports/dwell${reportQs(q)}`),
};

function reportQs(q: ReportQuery): string {
  const p = new URLSearchParams();
  if (q.from) p.set("from", String(q.from));
  if (q.to) p.set("to", String(q.to));
  if (q.route) p.set("route", q.route);
  if (q.driver) p.set("driver", q.driver);
  if (q.siteId) p.set("siteId", q.siteId);
  const s = p.toString();
  return s ? `?${s}` : "";
}

export const STATUS_COLOR: Record<VehicleStatus, string> = {
  AVAILABLE: "#1d9e75",
  EN_ROUTE: "#2f5fda",
  ON_SITE: "#c98a1f",
  OFFLINE: "#b0b5bb",
};

export const STATUS_LABEL: Record<VehicleStatus, string> = {
  AVAILABLE: "Available",
  EN_ROUTE: "En route",
  ON_SITE: "On site",
  OFFLINE: "Offline",
};

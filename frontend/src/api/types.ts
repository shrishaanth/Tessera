export type VehicleStatus = "ACTIVE" | "ON_SITE" | "OFFLINE";

export interface Vehicle {
  vehicleId: string;
  driverName: string | null;
  status: VehicleStatus;
  latitude: number;
  longitude: number;
  headingDeg: number;
  speedKph: number;
  lastReportEpochMs: number;
}

export interface StatusChange {
  status: VehicleStatus;
  epochMillis: number;
}

export interface GeofenceEventRecord {
  vehicleId: string;
  siteId: string;
  type: "ENTER" | "EXIT";
  epochMillis: number;
  dwellSeconds: number | null;
}

export interface VehicleDetail {
  vehicle: Vehicle;
  onSiteName: string | null;
  recentGeofenceEvents: GeofenceEventRecord[];
  statusHistory: StatusChange[];
}

export interface SiteView {
  id: string;
  name: string;
  address: string | null;
  kind: "POLYGON" | "RADIUS";
  outline: [number, number][];
  centerLat: number | null;
  centerLon: number | null;
  radiusMeters: number | null;
  dwellAlertSeconds: number | null;
  createdAtEpochMs: number;
}

export interface SiteDefinition {
  name: string;
  address?: string | null;
  polygon?: [number, number][] | null;
  centerLat?: number | null;
  centerLon?: number | null;
  radiusMeters?: number | null;
  dwellAlertSeconds?: number | null;
}

export interface Alert {
  id: string;
  type: "DWELL_EXCEEDED";
  severity: "INFO" | "WARNING";
  vehicleId: string | null;
  siteId: string | null;
  message: string;
  createdAtEpochMs: number;
  acknowledged: boolean;
}

export interface DataSourceInfo {
  key: string;
  name: string;
  provider: string;
  purpose: string;
  role: "PRODUCTION" | "SUBSTITUTE";
  disclosure: string;
  active: boolean;
}

export interface Identity {
  username: string;
  role: string;
}

// ---- Reporting (FR-4): average dwell time per site ----

export interface Trend {
  previousValue: number | null;
  deltaValue: number | null;
  direction: "up" | "down" | "flat";
}

export interface SiteDwell {
  siteId: string;
  siteName: string;
  visits: number;
  avgDwellSeconds: number | null;
  enoughData: boolean;
}

export interface DwellReport {
  fromEpochMs: number;
  toEpochMs: number;
  totalVisits: number;
  overallAvgDwellSeconds: number | null;
  bySite: SiteDwell[];
  trend: Trend;
  provisional: boolean;
}

export interface Readiness {
  ready: boolean;
  collectionDays: number;
  minCollectionDays: number;
  siteExits: number;
  minSiteExits: number;
  reasons: string[];
  syntheticHistory: boolean;
}

export interface ReportFilterOptions {
  sites: { id: string; name: string }[];
}

// ---- Search, geocoding, replay (FR-6, FR-5) ----

export interface GeocodeResult {
  displayName: string;
  latitude: number;
  longitude: number;
  category: string;
  type: string;
  importance: number;
}

export interface GeocodeResponse {
  query: string;
  results: GeocodeResult[];
  degraded: boolean;
}

export interface ReplayVehicle {
  vehicleId: string;
  driverName: string | null;
}

export interface TrajectoryPoint {
  latitude: number;
  longitude: number;
  epochMillis: number;
  speedKph: number;
  headingDeg: number;
}

export interface Trajectory {
  vehicleId: string;
  fromEpochMs: number;
  toEpochMs: number;
  totalPoints: number;
  sampled: boolean;
  points: TrajectoryPoint[];
}

export interface FleetFrame {
  type: "fleet";
  ts: number;
  vehicles: Vehicle[];
}

export interface GeofenceEventFrameData {
  vehicleId: string;
  siteId: string;
  siteName: string;
  eventType: "ENTER" | "EXIT";
  epochMillis: number;
  dwellSeconds: number;
}

export interface GeofenceFrame {
  type: "geofence";
  ts: number;
  event: GeofenceEventFrameData;
}

export interface AlertFrame {
  type: "alert";
  ts: number;
  alert: Alert;
}

export type LiveFrame = FleetFrame | GeofenceFrame | AlertFrame;

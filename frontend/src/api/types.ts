export type VehicleStatus = "AVAILABLE" | "EN_ROUTE" | "ON_SITE" | "OFFLINE";

export interface Vehicle {
  vehicleId: string;
  driverName: string | null;
  status: VehicleStatus;
  latitude: number;
  longitude: number;
  headingDeg: number;
  speedKph: number;
  lastReportEpochMs: number;
  currentJobId: string | null;
}

export interface StatusChange {
  status: VehicleStatus;
  epochMillis: number;
}

export interface Job {
  id: string;
  destinationAddress: string | null;
  destLatitude: number;
  destLongitude: number;
  assignedVehicleId: string | null;
  status: "UNASSIGNED" | "ASSIGNED" | "COMPLETED" | "CANCELLED";
  createdAtEpochMs: number;
  assignedAtEpochMs: number;
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
  currentJob: Job | null;
  etaSeconds: number | null;
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

export interface NearestVehicle {
  vehicleId: string;
  driverName: string | null;
  straightLineMeters: number;
  travelSeconds: number;
  latitude: number;
  longitude: number;
}

export interface CreateJobResponse {
  job: Job;
  nearestAvailable: NearestVehicle[];
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

// ---- Phase 3: reporting (FR-4) ----

export interface Trend {
  previousValue: number | null;
  deltaValue: number | null;
  direction: "up" | "down" | "flat";
}

export interface WeekPoint {
  weekStartEpochMs: number;
  completed: number;
  onTime: number;
  onTimePct: number | null;
}

export interface OnTimeReport {
  fromEpochMs: number;
  toEpochMs: number;
  completed: number;
  onTime: number;
  onTimePct: number | null;
  byWeek: WeekPoint[];
  trend: Trend;
  provisional: boolean;
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
  completedJobs: number;
  minCompletedJobs: number;
  siteExits: number;
  minSiteExits: number;
  reasons: string[];
  syntheticHistory: boolean;
}

export interface ReportFilterOptions {
  routes: string[];
  drivers: string[];
  sites: { id: string; name: string }[];
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

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

export interface VehicleDetail {
  vehicle: Vehicle;
  currentJob: Job | null;
  etaSeconds: number | null;
  statusHistory: StatusChange[];
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

export interface FleetFrame {
  type: "fleet";
  ts: number;
  vehicles: Vehicle[];
}

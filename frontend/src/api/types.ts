/**
 * The shapes the reporting API serves.
 *
 * These mirror the Java records in `reporting-api`, and nothing in either build
 * connects the two — a renamed field compiles on both sides and breaks only at run
 * time. `FleetControllerTest` pins the names from the Java side; this file is the
 * other half of that agreement.
 */

/** Risk banded for display. The service decides the bands, not the client. */
export type RiskLevel = "LOW" | "MODERATE" | "HIGH" | "SEVERE";

export type RiskTier = "SAFE" | "AVERAGE" | "RISKY";

/** One vehicle's risk state for one window. */
export interface VehicleRisk {
  vehicleId: string;
  driverId: string;
  driverName: string;
  riskTier: RiskTier;
  windowStart: number;
  windowEnd: number;
  readingCount: number;
  hardBrakeCount: number;
  speedViolationCount: number;
  incidentCount: number;
  avgSpeedKph: number;
  maxSpeedKph: number;
  avgOverLimitRatio: number;
  maxSeverity: number;
  movingRatio: number;
  lat: number;
  lon: number;
  headingDeg: number;
  lastReadingTs: number;
  riskScore: number;
  riskLevel: RiskLevel;
  /**
   * Null until a model has been trained — not zero.
   *
   * A probability of 0 means the model is confident nothing will happen; null
   * means no model has run. Rendering the second as the first would be inventing
   * a prediction, so every use of these fields checks `modelScored` first.
   */
  predictedLabel: number | null;
  probability: number | null;
  modelScored: boolean;
}

export interface FleetSnapshot {
  generatedAt: number;
  vehicleCount: number;
  /** Whether any vehicle carries a model prediction. */
  modelScored: boolean;
  vehicles: VehicleRisk[];
}

/** One road segment's traffic and risk for one window. */
export interface SegmentRisk {
  segmentId: string;
  windowStart: number;
  windowEnd: number;
  readingCount: number;
  vehicleCount: number;
  avgSpeedKph: number;
  speedLimitKph: number;
  complianceRatio: number;
  hardBrakeCount: number;
  speedViolationCount: number;
  incidentCount: number;
  riskIndex: number;
  riskLevel: RiskLevel;
}

/** A notification that a vehicle crossed a risk threshold. */
export interface RiskAlert {
  vehicleId: string;
  driverId: string;
  riskTier: RiskTier;
  windowStart: number;
  windowEnd: number;
  riskScore: number;
  hardBrakeCount: number;
  speedViolationCount: number;
  incidentCount: number;
  /** The score is a rate, so its denominator travels with it. */
  readingCount: number;
  reason: string;
  raisedAt: number;
}

/** The simulated-data disclosure (FR-6.1), served rather than hard-coded here. */
export interface DataSourceInfo {
  simulated: boolean;
  headline: string;
  details: string[];
}

/** Whether the alert feed is actually working, as opposed to merely quiet. */
export interface AlertStatus {
  consumerRunning: boolean;
  alertsConsumed: number;
  malformedDiscarded: number;
  buffered: number;
  connectedDashboards: number;
}

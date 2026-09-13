import type { RiskLevel } from "../api/types";

/**
 * Colours for the four risk bands.
 *
 * <p>The bands themselves are decided by the service, so every client agrees on
 * which vehicles are "high risk". Only their appearance is chosen here.
 *
 * <p>Green through red, because the meaning is ordered and any other palette would
 * make the reader learn a mapping. The three upper bands are distinguishable in
 * lightness as well as hue, so red-green colour blindness does not collapse the
 * distinction that matters most — a severe vehicle stays obviously darker than a
 * moderate one.
 */
export const RISK_COLOURS: Record<RiskLevel, string> = {
  LOW: "#1d9e75",
  MODERATE: "#c98a1f",
  HIGH: "#e06c2b",
  SEVERE: "#c2281f",
};

export const RISK_ORDER: RiskLevel[] = ["SEVERE", "HIGH", "MODERATE", "LOW"];

export function riskColour(level: RiskLevel): string {
  return RISK_COLOURS[level] ?? RISK_COLOURS.LOW;
}

/** Marker radius grows with risk, so severity reads without relying on colour alone. */
export function riskRadius(level: RiskLevel): number {
  switch (level) {
    case "SEVERE":
      return 11;
    case "HIGH":
      return 9;
    case "MODERATE":
      return 7;
    default:
      return 6;
  }
}

/** "3m ago" — relative times are easier to judge for staleness than clock times. */
export function timeAgo(epochMillis: number, now = Date.now()): string {
  const seconds = Math.max(0, Math.round((now - epochMillis) / 1000));
  if (seconds < 10) {
    return "just now";
  }
  if (seconds < 60) {
    return `${seconds}s ago`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes}m ago`;
  }
  const hours = Math.floor(minutes / 60);
  return hours < 24 ? `${hours}h ago` : `${Math.floor(hours / 24)}d ago`;
}

/**
 * A model probability as a percentage, or a dash when no model has run.
 *
 * <p>The dash is load-bearing. A null probability means no model has been trained;
 * showing it as "0%" would claim the model is confident nothing will happen, which
 * is a different statement and one nothing has made.
 */
export function formatProbability(probability: number | null, scored: boolean): string {
  if (!scored || probability === null || probability === undefined) {
    return "—";
  }
  return `${Math.round(probability * 100)}%`;
}

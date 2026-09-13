import { useEffect, useState } from "react";

import { api } from "../api/client";
import type { VehicleRisk } from "../api/types";
import { formatProbability, riskColour, timeAgo } from "./risk";

interface Props {
  vehicle: VehicleRisk | null;
  onClose: () => void;
}

/** Windows of history to chart — thirty minutes at a one-minute slide. */
const HISTORY_WINDOWS = 30;

/**
 * One vehicle's current state and recent risk history.
 *
 * <h2>Why the history is a sparkline and not a chart library</h2>
 * It is thirty numbers in a strip an inch wide. An SVG polyline draws that in a
 * dozen lines; a charting dependency would add hundreds of kilobytes to render
 * something with no axes, legend or interaction.
 *
 * <p>The history comes from HBase through `/history`, which is a short scan
 * precisely because the row key sorts newest-first — the same design that makes the
 * fleet snapshot cheap.
 */
export function VehicleDetail({ vehicle, onClose }: Props) {
  const [history, setHistory] = useState<VehicleRisk[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!vehicle) {
      setHistory([]);
      return;
    }
    const controller = new AbortController();
    api
      .vehicleHistory(vehicle.vehicleId, HISTORY_WINDOWS, controller.signal)
      .then((rows) => {
        setHistory(rows);
        setError(null);
      })
      .catch((cause: Error) => {
        if (cause.name !== "AbortError") {
          setError(cause.message);
        }
      });
    return () => controller.abort();
    // Re-fetch when the selection changes or the vehicle's latest window advances.
  }, [vehicle?.vehicleId, vehicle?.windowStart]);

  if (!vehicle) {
    return (
      <div className="panel detail detail--empty">
        <p className="muted">Select a vehicle on the map or in the list.</p>
      </div>
    );
  }

  return (
    <div className="panel detail">
      <div className="panel__header">
        <h2>
          <span
            className="dot"
            style={{ background: riskColour(vehicle.riskLevel) }}
            aria-hidden="true"
          />
          {vehicle.vehicleId}
        </h2>
        <button type="button" className="detail__close" onClick={onClose} aria-label="Close">
          ×
        </button>
      </div>

      <p className="detail__driver">
        {vehicle.driverName} <span className="tier">{vehicle.riskTier}</span>
      </p>

      <dl className="detail__stats">
        <div>
          <dt>Risk score</dt>
          <dd className="strong">{Math.round(vehicle.riskScore)}</dd>
        </div>
        <div>
          <dt title="Model's calibrated chance of an incident in the next five minutes">Incident chance</dt>
          {/* Absence is shown as a dash and named, rather than rendered as zero. */}
          <dd>{formatProbability(vehicle.probability, vehicle.modelScored)}</dd>
        </div>
        <div>
          <dt>Hard brakes</dt>
          <dd>{vehicle.hardBrakeCount}</dd>
        </div>
        <div>
          <dt>Violations</dt>
          <dd>{vehicle.speedViolationCount}</dd>
        </div>
        <div>
          <dt>Incidents</dt>
          <dd>{vehicle.incidentCount}</dd>
        </div>
        <div>
          <dt>Readings</dt>
          {/* The score is a rate, so its denominator is shown next to it. */}
          <dd>{vehicle.readingCount}</dd>
        </div>
        <div>
          <dt>Avg speed</dt>
          <dd>{vehicle.avgSpeedKph.toFixed(1)} km/h</dd>
        </div>
        <div>
          <dt>Peak speed</dt>
          <dd>{vehicle.maxSpeedKph.toFixed(1)} km/h</dd>
        </div>
      </dl>

      {!vehicle.modelScored && (
        <p className="detail__note">
          No model has scored this vehicle yet. Run the archive generator, batch job
          and training to add a prediction.
        </p>
      )}

      <h3 className="detail__section">Risk, last {history.length} windows</h3>
      {error ? (
        <p className="detail__note">History unavailable: {error}</p>
      ) : (
        <Sparkline history={history} />
      )}
      <p className="muted small">Last reading {timeAgo(vehicle.lastReadingTs)}</p>
    </div>
  );
}

/**
 * Risk over time, oldest to newest.
 *
 * <p>The API returns newest-first, because that is how the row key sorts and how
 * the feed reads; a time series has to be reversed to run left to right.
 */
function Sparkline({ history }: { history: VehicleRisk[] }) {
  if (history.length < 2) {
    return <p className="muted small">Not enough history yet.</p>;
  }
  const series = [...history].reverse();
  const width = 260;
  const height = 48;
  const step = width / (series.length - 1);
  const points = series
    .map((row, index) => {
      // The score is already bounded to 0..100, so the axis is fixed rather than
      // scaled to the data — a sparkline that rescales makes a calm stretch look
      // identical to a dangerous one.
      const y = height - (Math.min(100, Math.max(0, row.riskScore)) / 100) * height;
      return `${(index * step).toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");

  return (
    <svg
      className="sparkline"
      viewBox={`0 0 ${width} ${height}`}
      role="img"
      aria-label={`Risk score over the last ${series.length} windows`}
    >
      <polyline points={points} fill="none" stroke="#2f5fda" strokeWidth="2" />
    </svg>
  );
}

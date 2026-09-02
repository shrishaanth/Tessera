import { useState } from "react";
import { api, ApiError } from "../api/client";
import type { NearestVehicle } from "../api/types";

interface Props {
  jobDraft: { lat: number; lon: number } | null;
  jobId: string | null;
  shortlist: NearestVehicle[];
  onStart: () => void;
  onCancel: () => void;
  onAssigned: () => void;
}

function fmtEta(seconds: number): string {
  const m = Math.round(seconds / 60);
  return m < 1 ? "<1 min" : `${m} min`;
}

export function NewJobPanel({ jobDraft, jobId, shortlist, onStart, onCancel, onAssigned }: Props) {
  const [assigning, setAssigning] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const assign = async (vehicleId: string) => {
    if (!jobId) return;
    setAssigning(vehicleId);
    setError(null);
    try {
      await api.assignJob(jobId, vehicleId);
      onAssigned();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Assignment failed.");
    } finally {
      setAssigning(null);
    }
  };

  if (!jobDraft) {
    return (
      <section>
        <div className="heading-md">New job</div>
        <p style={{ color: "var(--neutral-500)", fontSize: 12 }}>
          Click a point on the map to set a job location and get the nearest
          available vehicles, ranked by road-network drive time.
        </p>
        <button className="btn" onClick={onStart}>
          Pick location on map
        </button>
      </section>
    );
  }

  return (
    <section>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <span className="heading-md">Nearest available</span>
        <button className="btn secondary" style={{ padding: "3px 8px" }} onClick={onCancel}>
          Cancel
        </button>
      </div>
      <div className="kv">
        <span>Job location</span>
        <span className="mono">
          {jobDraft.lat.toFixed(5)}, {jobDraft.lon.toFixed(5)}
        </span>
      </div>
      {error && <div className="err" style={{ color: "var(--status-danger)", fontSize: 12 }}>{error}</div>}
      {shortlist.length === 0 && (
        <div style={{ fontSize: 12, color: "var(--neutral-500)" }}>
          No available vehicles in range.
        </div>
      )}
      {shortlist.map((s, i) => (
        <div className="rank" key={s.vehicleId}>
          <div>
            <div style={{ fontWeight: 700 }}>
              {i + 1}. {s.vehicleId}
            </div>
            <div style={{ fontSize: 11, color: "var(--neutral-500)" }}>
              {s.driverName ?? "—"} · {(s.straightLineMeters / 1000).toFixed(1)} km direct
            </div>
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <span className="eta">{fmtEta(s.travelSeconds)}</span>
            <button
              className="btn"
              disabled={assigning !== null}
              onClick={() => void assign(s.vehicleId)}
            >
              {assigning === s.vehicleId ? "…" : "Assign"}
            </button>
          </div>
        </div>
      ))}
    </section>
  );
}

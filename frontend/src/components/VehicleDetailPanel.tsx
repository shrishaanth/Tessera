import { useEffect, useState } from "react";
import { api } from "../api/client";
import { STATUS_COLOR, STATUS_LABEL } from "../api/client";
import type { VehicleDetail } from "../api/types";

function fmtEta(seconds: number | null): string {
  if (seconds == null) return "—";
  const m = Math.round(seconds / 60);
  return m < 1 ? "<1 min" : `${m} min`;
}

function fmtClock(ms: number): string {
  return new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

export function VehicleDetailPanel({ vehicleId, onClose }: { vehicleId: string; onClose: () => void }) {
  const [detail, setDetail] = useState<VehicleDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    const load = () =>
      api
        .vehicleDetail(vehicleId)
        .then((d) => alive && setDetail(d))
        .catch(() => alive && setError("Could not load vehicle detail."));
    load();
    const t = setInterval(load, 3000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, [vehicleId]);

  if (error) return <section>{error}</section>;
  if (!detail) return <section>Loading {vehicleId}…</section>;

  const v = detail.vehicle;
  return (
    <section>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <span className="heading-md">{v.vehicleId}</span>
        <button className="btn secondary" onClick={onClose} style={{ padding: "3px 8px" }}>
          Close
        </button>
      </div>
      <div className="kv">
        <span>Status</span>
        <span style={{ color: STATUS_COLOR[v.status], fontWeight: 700 }}>
          {STATUS_LABEL[v.status]}
        </span>
      </div>
      <div className="kv">
        <span>Driver</span>
        <span>{v.driverName ?? "—"}</span>
      </div>
      <div className="kv">
        <span>Speed</span>
        <span className="mono">{Number.isFinite(v.speedKph) ? `${v.speedKph.toFixed(0)} km/h` : "—"}</span>
      </div>
      <div className="kv">
        <span>Last report</span>
        <span className="mono">{fmtClock(v.lastReportEpochMs)}</span>
      </div>

      {detail.onSiteName && (
        <div className="kv">
          <span>On site</span>
          <span style={{ color: "var(--status-on-site)", fontWeight: 700 }}>
            {detail.onSiteName}
          </span>
        </div>
      )}
      <div className="kv">
        <span>Current job</span>
        <span>{detail.currentJob ? detail.currentJob.id : "—"}</span>
      </div>
      {detail.currentJob && (
        <>
          <div className="kv">
            <span>Destination</span>
            <span>
              {detail.currentJob.destinationAddress ??
                `${detail.currentJob.destLatitude.toFixed(4)}, ${detail.currentJob.destLongitude.toFixed(4)}`}
            </span>
          </div>
          <div className="kv">
            <span>ETA</span>
            <span className="mono">{fmtEta(detail.etaSeconds)}</span>
          </div>
        </>
      )}

      {detail.recentGeofenceEvents.length > 0 && (
        <>
          <div className="label-xs" style={{ marginTop: 12 }}>
            Recent site visits
          </div>
          {detail.recentGeofenceEvents.map((e, i) => (
            <div className="hist" key={i}>
              <span className="t">{fmtClock(e.epochMillis)}</span>
              <span>
                {e.type === "ENTER" ? "entered" : "left"} {e.siteId}
                {e.type === "EXIT" && e.dwellSeconds != null
                  ? ` · ${Math.round(e.dwellSeconds / 60)} min`
                  : ""}
              </span>
            </div>
          ))}
        </>
      )}

      <div className="label-xs" style={{ marginTop: 12 }}>
        Recent status history
      </div>
      {detail.statusHistory.length === 0 && <div className="hist">No transitions recorded yet.</div>}
      {[...detail.statusHistory].reverse().map((h, i) => (
        <div className="hist" key={i}>
          <span className="t">{fmtClock(h.epochMillis)}</span>
          <span style={{ color: STATUS_COLOR[h.status], fontWeight: 700 }}>
            {STATUS_LABEL[h.status]}
          </span>
        </div>
      ))}
    </section>
  );
}

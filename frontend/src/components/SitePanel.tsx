import { useState } from "react";
import type { SiteView } from "../api/types";

export type SiteDrawMode = "idle" | "sitePoly" | "siteRadius";

interface Props {
  sites: SiteView[];
  mode: SiteDrawMode;
  drawPoints: [number, number][];
  radiusCenter: [number, number] | null;
  onStartPolygon: () => void;
  onStartRadius: () => void;
  onCancel: () => void;
  onFinishPolygon: (name: string, dwellAlertSeconds: number | null) => Promise<void>;
  onFinishRadius: (name: string, meters: number, dwellAlertSeconds: number | null) => Promise<void>;
  onDelete: (id: string) => Promise<void>;
}

export function SitePanel({
  sites,
  mode,
  drawPoints,
  radiusCenter,
  onStartPolygon,
  onStartRadius,
  onCancel,
  onFinishPolygon,
  onFinishRadius,
  onDelete,
}: Props) {
  const [name, setName] = useState("");
  const [meters, setMeters] = useState(150);
  const [dwell, setDwell] = useState("");
  const [busy, setBusy] = useState(false);

  const dwellSeconds = dwell.trim() === "" ? null : Math.max(1, Math.round(Number(dwell) * 60));

  const finish = async () => {
    setBusy(true);
    try {
      if (mode === "sitePoly") await onFinishPolygon(name.trim(), dwellSeconds);
      else await onFinishRadius(name.trim(), meters, dwellSeconds);
      setName("");
      setDwell("");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section>
      <div className="heading-md">Customer sites</div>

      {mode === "idle" && (
        <>
          <p style={{ fontSize: 12, color: "var(--neutral-500)" }}>
            Geofenced sites. Vehicles inside a site show as <em>on site</em>; a long
            dwell raises an alert.
          </p>
          <div style={{ display: "flex", gap: 6, marginBottom: 10 }}>
            <button className="btn secondary" onClick={onStartPolygon}>
              Draw polygon
            </button>
            <button className="btn secondary" onClick={onStartRadius}>
              Drop radius
            </button>
          </div>
        </>
      )}

      {mode !== "idle" && (
        <div className="card" style={{ padding: 10, marginBottom: 10 }}>
          <div style={{ fontSize: 12, marginBottom: 6 }}>
            {mode === "sitePoly"
              ? `Click the map to add boundary points (${drawPoints.length} placed; 3+ needed).`
              : radiusCenter
                ? "Centre set. Set the radius and name, then create."
                : "Click the map to set the site centre."}
          </div>
          <input
            className="login-input"
            placeholder="Site name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            style={{ width: "100%", padding: 7, marginBottom: 6, borderRadius: 6, border: "1px solid var(--neutral-300)" }}
          />
          {mode === "siteRadius" && (
            <label style={{ display: "block", fontSize: 12, marginBottom: 6 }}>
              Radius (m):{" "}
              <input
                type="number"
                value={meters}
                min={20}
                max={2000}
                onChange={(e) => setMeters(Number(e.target.value))}
                style={{ width: 80 }}
              />
            </label>
          )}
          <label style={{ display: "block", fontSize: 12, marginBottom: 8 }}>
            Dwell alert (min, optional):{" "}
            <input
              type="number"
              value={dwell}
              min={1}
              onChange={(e) => setDwell(e.target.value)}
              style={{ width: 70 }}
            />
          </label>
          <div style={{ display: "flex", gap: 6 }}>
            <button
              className="btn"
              disabled={
                busy ||
                !name.trim() ||
                (mode === "sitePoly" ? drawPoints.length < 3 : !radiusCenter)
              }
              onClick={() => void finish()}
            >
              {busy ? "…" : "Create site"}
            </button>
            <button className="btn secondary" onClick={onCancel}>
              Cancel
            </button>
          </div>
        </div>
      )}

      {sites.length === 0 && mode === "idle" && (
        <div style={{ fontSize: 12, color: "var(--neutral-500)" }}>No sites defined yet.</div>
      )}
      {sites.map((s) => (
        <div className="rank" key={s.id}>
          <div>
            <div style={{ fontWeight: 700 }}>{s.name}</div>
            <div style={{ fontSize: 11, color: "var(--neutral-500)" }}>
              {s.kind === "RADIUS" ? `radius ${Math.round(s.radiusMeters ?? 0)} m` : "polygon"}
              {s.dwellAlertSeconds ? ` · alert ${Math.round(s.dwellAlertSeconds / 60)} min` : ""}
            </div>
          </div>
          <button
            className="btn secondary"
            style={{ padding: "3px 8px" }}
            onClick={() => void onDelete(s.id)}
          >
            Delete
          </button>
        </div>
      ))}
    </section>
  );
}

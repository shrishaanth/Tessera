import { useEffect, useMemo, useRef, useState } from "react";
import { api } from "../api/client";
import type { ReplayVehicle, Trajectory } from "../api/types";
import { ReplayMap } from "./ReplayMap";
import { PlaybackControls } from "./PlaybackControls";

function todayUtc(): string {
  return new Date().toISOString().slice(0, 10);
}

/** Incident-review trajectory replay (FR-5): pick a vehicle + a day, watch the path. */
export function ReplayView() {
  const [vehicles, setVehicles] = useState<ReplayVehicle[]>([]);
  const [vehicleId, setVehicleId] = useState("");
  const [date, setDate] = useState(todayUtc());
  const [trajectory, setTrajectory] = useState<Trajectory | null>(null);
  const [index, setIndex] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(8);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setInterval>>();

  useEffect(() => {
    api
      .replayVehicles()
      .then((v) => {
        setVehicles(v);
        if (v.length && !vehicleId) setVehicleId(v[0].vehicleId);
      })
      .catch(() => {});
  }, []);

  const points = trajectory?.points ?? [];
  const atEnd = points.length > 0 && index >= points.length - 1;

  useEffect(() => {
    clearInterval(timer.current);
    if (!playing || points.length === 0) return;
    timer.current = setInterval(() => {
      setIndex((i) => {
        if (i >= points.length - 1) {
          setPlaying(false);
          return i;
        }
        return i + 1;
      });
    }, Math.max(16, Math.round(1000 / speed)));
    return () => clearInterval(timer.current);
  }, [playing, speed, points.length]);

  const load = async () => {
    if (!vehicleId) return;
    setLoading(true);
    setError(null);
    setPlaying(false);
    try {
      const t = await api.trajectory({ vehicleId, date });
      setTrajectory(t);
      setIndex(0);
    } catch {
      setError("Could not load trajectory.");
      setTrajectory(null);
    } finally {
      setLoading(false);
    }
  };

  const timeLabel = useMemo(() => {
    const p = points[Math.min(index, points.length - 1)];
    return p ? new Date(p.epochMillis).toLocaleTimeString() : "—";
  }, [points, index]);

  return (
    <div className="replay">
      <div className="replay-bar">
        <h2 className="heading-lg" style={{ margin: 0 }}>
          Trajectory replay
        </h2>
        <select
          value={vehicleId}
          onChange={(e) => setVehicleId(e.target.value)}
          aria-label="Vehicle"
        >
          {vehicles.length === 0 && <option value="">No vehicles</option>}
          {vehicles.map((v) => (
            <option key={v.vehicleId} value={v.vehicleId}>
              {v.vehicleId}
              {v.driverName ? ` · ${v.driverName}` : ""}
            </option>
          ))}
        </select>
        <input
          type="date"
          value={date}
          max={todayUtc()}
          onChange={(e) => setDate(e.target.value)}
          aria-label="Date"
        />
        <button className="btn" onClick={() => void load()} disabled={loading || !vehicleId}>
          {loading ? "Loading…" : "Load"}
        </button>
        {trajectory && (
          <span className="replay-meta">
            {trajectory.totalPoints} points
            {trajectory.sampled ? " (sampled)" : ""}
          </span>
        )}
      </div>

      {error && <div className="report-banner warn">{error}</div>}

      {!trajectory && !loading && (
        <div className="replay-empty">
          Pick a vehicle and a date, then <strong>Load</strong> to replay its recorded path.
        </div>
      )}

      {trajectory && trajectory.totalPoints === 0 && (
        <div className="replay-empty">No recorded positions for {vehicleId} on {date}.</div>
      )}

      {trajectory && trajectory.totalPoints > 0 && (
        <>
          <div className="replay-map">
            <ReplayMap points={points} index={index} />
          </div>
          <PlaybackControls
            count={points.length}
            index={index}
            playing={playing}
            speed={speed}
            atEnd={atEnd}
            timeLabel={timeLabel}
            onIndex={setIndex}
            onTogglePlay={() => {
              if (atEnd) setIndex(0);
              setPlaying((p) => !p);
            }}
            onSpeed={setSpeed}
          />
        </>
      )}
    </div>
  );
}

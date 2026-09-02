import { useMemo, useState } from "react";
import { useLiveFleet } from "../hooks/useLiveFleet";
import { api } from "../api/client";
import type { NearestVehicle, VehicleStatus } from "../api/types";
import { MapCanvas } from "./MapCanvas";
import { StatusFilterBar } from "./StatusFilterBar";
import { VehicleDetailPanel } from "./VehicleDetailPanel";
import { NewJobPanel } from "./NewJobPanel";

const ALL: VehicleStatus[] = ["AVAILABLE", "EN_ROUTE", "ON_SITE", "OFFLINE"];

export function LiveMapView() {
  const { vehicles, connected, lastUpdateMs } = useLiveFleet(true);

  const [filter, setFilter] = useState<Set<VehicleStatus>>(new Set(ALL));
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const [jobMode, setJobMode] = useState(false);
  const [jobDraft, setJobDraft] = useState<{ lat: number; lon: number } | null>(null);
  const [jobId, setJobId] = useState<string | null>(null);
  const [shortlist, setShortlist] = useState<NearestVehicle[]>([]);

  const visible = useMemo(
    () => vehicles.filter((v) => filter.has(v.status)),
    [vehicles, filter],
  );

  const toggle = (s: VehicleStatus) => {
    setFilter((prev) => {
      const next = new Set(prev);
      next.has(s) ? next.delete(s) : next.add(s);
      return next;
    });
  };

  const onMapClick = async (lat: number, lon: number) => {
    if (!jobMode) return;
    setJobDraft({ lat, lon });
    setSelectedId(null);
    try {
      const res = await api.createJob(lat, lon);
      setJobId(res.job.id);
      setShortlist(res.nearestAvailable);
    } catch {
      setShortlist([]);
    }
  };

  const resetJob = () => {
    setJobMode(false);
    setJobDraft(null);
    setJobId(null);
    setShortlist([]);
  };

  const staleMs = lastUpdateMs ? Date.now() - lastUpdateMs : null;

  return (
    <div className="map-layout">
      <div className="map-wrap">
        <StatusFilterBar active={filter} onToggle={toggle} vehicles={vehicles} />
        <div
          style={{
            position: "absolute",
            zIndex: 500,
            right: 10,
            top: 10,
            background: "rgba(255,255,255,0.95)",
            borderRadius: 8,
            padding: "4px 8px",
            fontSize: 11,
            boxShadow: "0 1px 6px rgba(0,0,0,0.16)",
          }}
        >
          <span
            className="dot"
            style={{ background: connected ? "var(--status-available)" : "var(--status-danger)" }}
          />{" "}
          {connected ? "Live" : "Reconnecting…"}
          {staleMs != null && ` · ${(staleMs / 1000).toFixed(0)}s ago`} · {vehicles.length} vehicles
        </div>
        <MapCanvas
          vehicles={visible}
          selectedId={selectedId}
          onSelect={(id) => {
            setSelectedId(id);
            if (!jobMode) setJobDraft(null);
          }}
          jobDraft={jobDraft}
          onMapClick={onMapClick}
          shortlist={shortlist}
        />
      </div>

      <aside className="side">
        <NewJobPanel
          jobDraft={jobMode ? jobDraft : null}
          jobId={jobId}
          shortlist={shortlist}
          onStart={() => {
            setJobMode(true);
            setSelectedId(null);
          }}
          onCancel={resetJob}
          onAssigned={resetJob}
        />
        {selectedId && !jobMode && (
          <VehicleDetailPanel vehicleId={selectedId} onClose={() => setSelectedId(null)} />
        )}
      </aside>
    </div>
  );
}

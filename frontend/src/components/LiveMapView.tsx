import { useEffect, useMemo, useState } from "react";
import { useLiveStream } from "../live/LiveStreamContext";
import { api } from "../api/client";
import type { SiteView, VehicleStatus } from "../api/types";
import { MapCanvas } from "./MapCanvas";
import { StatusFilterBar } from "./StatusFilterBar";
import { VehicleDetailPanel } from "./VehicleDetailPanel";
import { AddressSearchBox } from "./AddressSearchBox";
import type { PickedLocation } from "./AddressSearchBox";
import { SitePanel } from "./SitePanel";
import type { SiteDrawMode } from "./SitePanel";

const ALL: VehicleStatus[] = ["ACTIVE", "ON_SITE", "OFFLINE"];

export function LiveMapView() {
  const { vehicles, connected, lastUpdateMs, geofenceFeed } = useLiveStream();

  const [filter, setFilter] = useState<Set<VehicleStatus>>(new Set(ALL));
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const [sites, setSites] = useState<SiteView[]>([]);
  const [siteMode, setSiteMode] = useState<SiteDrawMode>("idle");
  const [drawPoints, setDrawPoints] = useState<[number, number][]>([]);
  const [radiusCenter, setRadiusCenter] = useState<[number, number] | null>(null);
  const [focus, setFocus] = useState<[number, number] | null>(null);

  const refreshSites = () => api.sites().then(setSites).catch(() => {});
  useEffect(() => {
    refreshSites();
  }, []);

  const visible = useMemo(
    () => vehicles.filter((v) => filter.has(v.status)),
    [vehicles, filter],
  );

  const toggle = (s: VehicleStatus) =>
    setFilter((prev) => {
      const next = new Set(prev);
      next.has(s) ? next.delete(s) : next.add(s);
      return next;
    });

  const onMapClick = (lat: number, lon: number) => {
    if (siteMode === "sitePoly") {
      setDrawPoints((p) => [...p, [lat, lon]]);
      return;
    }
    if (siteMode === "siteRadius") {
      setRadiusCenter([lat, lon]);
    }
  };

  const onSearchPick = (loc: PickedLocation) => {
    resetSiteDraw();
    setFocus([loc.lat, loc.lon]);
  };

  const resetSiteDraw = () => {
    setSiteMode("idle");
    setDrawPoints([]);
    setRadiusCenter(null);
  };

  const finishPolygon = async (name: string, dwellAlertSeconds: number | null) => {
    await api.createSite({ name, polygon: drawPoints, dwellAlertSeconds });
    resetSiteDraw();
    refreshSites();
  };
  const finishRadius = async (name: string, meters: number, dwellAlertSeconds: number | null) => {
    if (!radiusCenter) return;
    await api.createSite({
      name,
      centerLat: radiusCenter[0],
      centerLon: radiusCenter[1],
      radiusMeters: meters,
      dwellAlertSeconds,
    });
    resetSiteDraw();
    refreshSites();
  };
  const deleteSite = async (id: string) => {
    await api.deleteSite(id);
    refreshSites();
  };

  const staleMs = lastUpdateMs ? Date.now() - lastUpdateMs : null;
  const recentGeofence = geofenceFeed.slice(0, 6);

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
          onSelect={setSelectedId}
          onMapClick={onMapClick}
          sites={sites}
          drawPoints={siteMode === "sitePoly" ? drawPoints : []}
          focus={focus}
        />
      </div>

      <aside className="side">
        <section>
          <div className="label-xs">Find a place</div>
          <AddressSearchBox onPick={onSearchPick} placeholder="Search an address or customer site…" />
        </section>

        <SitePanel
          sites={sites}
          mode={siteMode}
          drawPoints={drawPoints}
          radiusCenter={radiusCenter}
          onStartPolygon={() => {
            setDrawPoints([]);
            setSiteMode("sitePoly");
          }}
          onStartRadius={() => {
            setRadiusCenter(null);
            setSiteMode("siteRadius");
          }}
          onCancel={resetSiteDraw}
          onFinishPolygon={finishPolygon}
          onFinishRadius={finishRadius}
          onDelete={deleteSite}
        />

        {recentGeofence.length > 0 && (
          <section>
            <div className="label-xs">Recent geofence activity</div>
            {recentGeofence.map((e, i) => (
              <div className="hist" key={i}>
                <span className="t">{new Date(e.epochMillis).toLocaleTimeString()}</span>
                <span>
                  {e.vehicleId} {e.eventType === "ENTER" ? "entered" : "left"} {e.siteName}
                  {e.eventType === "EXIT" && e.dwellSeconds >= 0
                    ? ` (${Math.round(e.dwellSeconds / 60)} min)`
                    : ""}
                </span>
              </div>
            ))}
          </section>
        )}

        {selectedId && siteMode === "idle" && (
          <VehicleDetailPanel vehicleId={selectedId} onClose={() => setSelectedId(null)} />
        )}
      </aside>
    </div>
  );
}

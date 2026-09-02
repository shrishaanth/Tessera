import {
  Circle,
  CircleMarker,
  MapContainer,
  Polygon,
  Polyline,
  TileLayer,
  Tooltip,
  useMapEvents,
} from "react-leaflet";
import type { NearestVehicle, SiteView, Vehicle } from "../api/types";
import { STATUS_COLOR } from "../api/client";

interface Props {
  vehicles: Vehicle[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  jobDraft: { lat: number; lon: number } | null;
  onMapClick: (lat: number, lon: number) => void;
  shortlist: NearestVehicle[];
  sites: SiteView[];
  drawPoints: [number, number][];
}

function ClickCapture({ onClick }: { onClick: (lat: number, lon: number) => void }) {
  useMapEvents({ click: (e) => onClick(e.latlng.lat, e.latlng.lng) });
  return null;
}

const SITE_STYLE = { color: "#c98a1f", weight: 2, fillColor: "#c98a1f", fillOpacity: 0.12 };

export function MapCanvas({
  vehicles,
  selectedId,
  onSelect,
  jobDraft,
  onMapClick,
  shortlist,
  sites,
  drawPoints,
}: Props) {
  const shortlistIds = new Set(shortlist.map((s) => s.vehicleId));

  return (
    <MapContainer center={[42.3601, -71.0589]} zoom={15} preferCanvas>
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <ClickCapture onClick={onMapClick} />

      {sites.map((s) =>
        s.kind === "RADIUS" && s.centerLat != null && s.centerLon != null && s.radiusMeters ? (
          <Circle
            key={s.id}
            center={[s.centerLat, s.centerLon]}
            radius={s.radiusMeters}
            pathOptions={SITE_STYLE}
          >
            <Tooltip>{s.name}</Tooltip>
          </Circle>
        ) : (
          <Polygon key={s.id} positions={s.outline} pathOptions={SITE_STYLE}>
            <Tooltip>{s.name}</Tooltip>
          </Polygon>
        ),
      )}

      {drawPoints.length > 0 && (
        <>
          <Polyline positions={drawPoints} pathOptions={{ color: "#d64545", weight: 2, dashArray: "4" }} />
          {drawPoints.map((p, i) => (
            <CircleMarker
              key={i}
              center={p}
              radius={4}
              pathOptions={{ color: "#d64545", fillColor: "#fff", fillOpacity: 1 }}
            />
          ))}
        </>
      )}

      {vehicles.map((v) => {
        const selected = v.vehicleId === selectedId;
        const inShortlist = shortlistIds.has(v.vehicleId);
        return (
          <CircleMarker
            key={v.vehicleId}
            center={[v.latitude, v.longitude]}
            radius={selected ? 9 : inShortlist ? 7 : 5}
            pathOptions={{
              color: selected || inShortlist ? "#16181c" : STATUS_COLOR[v.status],
              weight: selected || inShortlist ? 2 : 1,
              fillColor: STATUS_COLOR[v.status],
              fillOpacity: v.status === "OFFLINE" ? 0.5 : 0.95,
            }}
            eventHandlers={{ click: () => onSelect(v.vehicleId) }}
          >
            <Tooltip direction="top" offset={[0, -4]}>
              <strong>{v.vehicleId}</strong>
              {v.driverName ? ` · ${v.driverName}` : ""} — {v.status}
            </Tooltip>
          </CircleMarker>
        );
      })}

      {jobDraft && (
        <CircleMarker
          center={[jobDraft.lat, jobDraft.lon]}
          radius={8}
          pathOptions={{ color: "#d64545", weight: 3, fillColor: "#d64545", fillOpacity: 0.3 }}
        >
          <Tooltip permanent direction="top" offset={[0, -6]}>
            Job location
          </Tooltip>
        </CircleMarker>
      )}
    </MapContainer>
  );
}

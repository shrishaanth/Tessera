import { useEffect } from "react";
import { CircleMarker, MapContainer, Polyline, TileLayer, Tooltip, useMap } from "react-leaflet";
import type { LatLngBoundsExpression } from "leaflet";
import type { TrajectoryPoint } from "../api/types";

function FitBounds({ points }: { points: TrajectoryPoint[] }) {
  const map = useMap();
  useEffect(() => {
    if (points.length < 2) return;
    const bounds = points.map((p) => [p.latitude, p.longitude]) as LatLngBoundsExpression;
    map.fitBounds(bounds, { padding: [30, 30] });
  }, [points, map]);
  return null;
}

interface Props {
  points: TrajectoryPoint[];
  index: number;
}

export function ReplayMap({ points, index }: Props) {
  const line = points.map((p) => [p.latitude, p.longitude]) as [number, number][];
  const head = points[Math.min(index, points.length - 1)];

  return (
    <MapContainer center={[42.3601, -71.0589]} zoom={14} preferCanvas>
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <FitBounds points={points} />
      {line.length > 1 && (
        <Polyline positions={line} pathOptions={{ color: "#2f5fda", weight: 3, opacity: 0.7 }} />
      )}
      {line.length > 0 && (
        <CircleMarker
          center={line[0]}
          radius={4}
          pathOptions={{ color: "#1d9e75", fillColor: "#1d9e75", fillOpacity: 1 }}
        >
          <Tooltip>Start</Tooltip>
        </CircleMarker>
      )}
      {head && (
        <CircleMarker
          center={[head.latitude, head.longitude]}
          radius={7}
          pathOptions={{ color: "#16181c", weight: 2, fillColor: "#2f5fda", fillOpacity: 1 }}
        >
          <Tooltip permanent direction="top" offset={[0, -6]}>
            {Number.isFinite(head.speedKph) ? `${head.speedKph.toFixed(0)} km/h` : "—"}
          </Tooltip>
        </CircleMarker>
      )}
    </MapContainer>
  );
}

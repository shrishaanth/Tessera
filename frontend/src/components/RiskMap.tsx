import { useEffect, useRef } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";

import type { VehicleRisk } from "../api/types";
import { riskColour, riskRadius } from "./risk";

interface Props {
  vehicles: VehicleRisk[];
  selectedId: string | null;
  onSelect: (vehicleId: string) => void;
}

/**
 * The fleet on a map, coloured by risk (FR-5.2).
 *
 * <h2>Why Leaflet directly rather than react-leaflet</h2>
 * The markers update every few seconds and there are only a few dozen of them.
 * Through React bindings each poll would reconcile a tree of marker components and
 * recreate the Leaflet layers underneath; here the markers are created once and
 * their position and style are mutated in place. The map keeps its pan and zoom
 * across updates for the same reason — a view that reset itself every five seconds
 * would be unusable.
 *
 * <h2>Circle markers, not pins</h2>
 * A pin's colour is a small part of its area; a filled circle is almost all colour,
 * which is what makes the risk band readable at a glance across two dozen vehicles.
 * The radius also grows with risk, so severity survives being printed in greyscale
 * or read by someone with a colour vision deficiency.
 */
export function RiskMap({ vehicles, selectedId, onSelect }: Props) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const markersRef = useRef<Map<string, L.CircleMarker>>(new Map());
  /** Latest handler, so the marker click closure never captures a stale one. */
  const onSelectRef = useRef(onSelect);
  onSelectRef.current = onSelect;

  /** Fit the view to the fleet once, then leave the operator's pan and zoom alone. */
  const fittedRef = useRef(false);

  useEffect(() => {
    if (!containerRef.current || mapRef.current) {
      return;
    }
    const map = L.map(containerRef.current, {
      zoomControl: true,
      attributionControl: true,
    }).setView([42.3601, -71.0589], 14);

    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19,
      attribution: "&copy; OpenStreetMap contributors",
    }).addTo(map);

    mapRef.current = map;
    return () => {
      map.remove();
      mapRef.current = null;
      markersRef.current.clear();
    };
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) {
      return;
    }
    const markers = markersRef.current;
    const seen = new Set<string>();

    for (const vehicle of vehicles) {
      // A vehicle with no position yet — written before the aggregate carried one,
      // or never reported — would otherwise be drawn at (0, 0), in the Atlantic.
      if (!Number.isFinite(vehicle.lat) || !Number.isFinite(vehicle.lon)
          || (vehicle.lat === 0 && vehicle.lon === 0)) {
        continue;
      }
      seen.add(vehicle.vehicleId);
      const selected = vehicle.vehicleId === selectedId;
      const style: L.CircleMarkerOptions = {
        radius: riskRadius(vehicle.riskLevel) + (selected ? 4 : 0),
        color: selected ? "#16181c" : "#ffffff",
        weight: selected ? 3 : 1.5,
        fillColor: riskColour(vehicle.riskLevel),
        fillOpacity: 0.9,
      };

      const existing = markers.get(vehicle.vehicleId);
      if (existing) {
        // Mutated in place: recreating the layer would drop the popup and make the
        // marker flicker on every poll.
        existing.setLatLng([vehicle.lat, vehicle.lon]);
        existing.setStyle(style);
        existing.setRadius(style.radius as number);
      } else {
        const marker = L.circleMarker([vehicle.lat, vehicle.lon], style)
          .addTo(map)
          .on("click", () => onSelectRef.current(vehicle.vehicleId));
        markers.set(vehicle.vehicleId, marker);
      }
      markers
        .get(vehicle.vehicleId)
        ?.bindTooltip(
          `${vehicle.vehicleId} · ${vehicle.driverName} · risk ${Math.round(vehicle.riskScore)}`,
          { direction: "top" },
        );
    }

    // Drop markers for vehicles that have left the snapshot, or the map would
    // accumulate ghosts at their last known positions.
    for (const [vehicleId, marker] of markers) {
      if (!seen.has(vehicleId)) {
        marker.remove();
        markers.delete(vehicleId);
      }
    }

    if (!fittedRef.current && seen.size > 0) {
      const bounds = L.latLngBounds(
        vehicles
          .filter((v) => seen.has(v.vehicleId))
          .map((v) => [v.lat, v.lon] as [number, number]),
      );
      map.fitBounds(bounds, { padding: [40, 40], maxZoom: 15 });
      fittedRef.current = true;
    }
  }, [vehicles, selectedId]);

  return <div className="map" ref={containerRef} role="application" aria-label="Fleet risk map" />;
}

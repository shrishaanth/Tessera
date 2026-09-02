import type { Vehicle, VehicleStatus } from "../api/types";
import { STATUS_COLOR, STATUS_LABEL } from "../api/client";

const ORDER: VehicleStatus[] = ["AVAILABLE", "EN_ROUTE", "ON_SITE", "OFFLINE"];

interface Props {
  active: Set<VehicleStatus>;
  onToggle: (s: VehicleStatus) => void;
  vehicles: Vehicle[];
}

export function StatusFilterBar({ active, onToggle, vehicles }: Props) {
  const counts = ORDER.reduce<Record<string, number>>((acc, s) => {
    acc[s] = vehicles.filter((v) => v.status === s).length;
    return acc;
  }, {});

  return (
    <div className="filterbar" role="group" aria-label="Filter vehicles by status">
      {ORDER.map((s) => (
        <button
          key={s}
          className={`chip ${active.has(s) ? "on" : ""}`}
          aria-pressed={active.has(s)}
          onClick={() => onToggle(s)}
        >
          <span className="dot" style={{ background: STATUS_COLOR[s] }} />
          {STATUS_LABEL[s]} <span className="mono">{counts[s]}</span>
        </button>
      ))}
    </div>
  );
}

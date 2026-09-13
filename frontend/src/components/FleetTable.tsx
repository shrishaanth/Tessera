import type { VehicleRisk } from "../api/types";
import { formatProbability, riskColour } from "./risk";

interface Props {
  vehicles: VehicleRisk[];
  selectedId: string | null;
  onSelect: (vehicleId: string) => void;
}

/**
 * The fleet as a sorted list, riskiest first.
 *
 * <p>Beside the map rather than instead of it. A map answers "where", a list
 * answers "who is worst" — and on a map the riskiest vehicle is wherever it happens
 * to be, which may be behind another marker or off the current view. Sorting by
 * score puts the vehicles that need attention at the top regardless of geography.
 */
export function FleetTable({ vehicles, selectedId, onSelect }: Props) {
  const sorted = [...vehicles].sort((a, b) => b.riskScore - a.riskScore);

  return (
    <div className="panel fleet">
      <div className="panel__header">
        <h2>Fleet</h2>
        <span className="panel__count">{vehicles.length} vehicles</span>
      </div>
      <div className="fleet__scroll">
        <table className="fleet__table">
          <thead>
            <tr>
              <th scope="col">Vehicle</th>
              <th scope="col">Driver</th>
              <th scope="col" className="numeric">Risk</th>
              <th scope="col" className="numeric">Model</th>
              <th scope="col" className="numeric">Brakes</th>
            </tr>
          </thead>
          <tbody>
            {sorted.map((vehicle) => (
              <tr
                key={vehicle.vehicleId}
                className={vehicle.vehicleId === selectedId ? "is-selected" : undefined}
                onClick={() => onSelect(vehicle.vehicleId)}
                tabIndex={0}
                role="button"
                onKeyDown={(event) => {
                  if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    onSelect(vehicle.vehicleId);
                  }
                }}
              >
                <td>
                  <span
                    className="dot"
                    style={{ background: riskColour(vehicle.riskLevel) }}
                    aria-hidden="true"
                  />
                  {vehicle.vehicleId}
                </td>
                <td className="muted">
                  {vehicle.driverName}
                  <span className="tier">{vehicle.riskTier}</span>
                </td>
                <td className="numeric strong">{Math.round(vehicle.riskScore)}</td>
                {/* A dash, never 0%, when no model has run — see formatProbability. */}
                <td className="numeric muted">
                  {formatProbability(vehicle.probability, vehicle.modelScored)}
                </td>
                <td className="numeric muted">{vehicle.hardBrakeCount}</td>
              </tr>
            ))}
            {sorted.length === 0 && (
              <tr>
                <td colSpan={5} className="empty">
                  No vehicles have reported yet. Start the producer and the streaming job.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

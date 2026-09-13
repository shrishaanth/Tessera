import type { RiskAlert } from "../api/types";
import { timeAgo } from "./risk";

interface Props {
  alerts: RiskAlert[];
  connected: boolean;
  retries: number;
  onSelect: (vehicleId: string) => void;
}

/**
 * The live alert feed (FR-5.3).
 *
 * <h2>An empty feed is ambiguous, so the connection state is shown</h2>
 * A calm fleet and a dead WebSocket look identical — both are an empty list. The
 * connection indicator is what separates "nothing is wrong" from "we would not know
 * if it were", which is the difference between an alerting system and a decoration.
 */
export function AlertFeed({ alerts, connected, retries, onSelect }: Props) {
  return (
    <div className="panel alerts">
      <div className="panel__header">
        <h2>Alerts</h2>
        <span className={connected ? "live live--on" : "live live--off"}>
          <span className="live__dot" aria-hidden="true" />
          {connected ? "live" : retries > 0 ? `reconnecting (${retries})` : "disconnected"}
        </span>
      </div>
      <ul className="alerts__list">
        {alerts.map((alert) => (
          <li
            key={`${alert.vehicleId}-${alert.raisedAt}`}
            className={alert.incidentCount > 0 ? "alert alert--incident" : "alert"}
            onClick={() => onSelect(alert.vehicleId)}
          >
            <div className="alert__top">
              <strong>{alert.vehicleId}</strong>
              <span className="tier">{alert.riskTier}</span>
              <span className="alert__when">{timeAgo(alert.raisedAt)}</span>
            </div>
            <p className="alert__reason">{alert.reason}</p>
          </li>
        ))}
        {alerts.length === 0 && (
          <li className="empty">
            {connected
              ? "No alerts. The feed is connected and the fleet is behaving."
              : "Not connected to the alert feed."}
          </li>
        )}
      </ul>
    </div>
  );
}

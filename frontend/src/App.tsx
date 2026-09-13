import { useMemo, useState } from "react";

import { AlertFeed } from "./components/AlertFeed";
import { DataSourceBanner } from "./components/DataSourceBanner";
import { FleetTable } from "./components/FleetTable";
import { RiskMap } from "./components/RiskMap";
import { VehicleDetail } from "./components/VehicleDetail";
import { RISK_COLOURS, RISK_ORDER, timeAgo } from "./components/risk";
import { useAlerts } from "./live/useAlerts";
import { useFleet } from "./live/useFleet";

/**
 * Tessera Risk — the operations dashboard.
 *
 * <p>One screen, because the question it answers is one question: which vehicles
 * need attention right now. The map says where, the list says who is worst, the
 * feed says what just happened, and selecting any of them selects the same vehicle
 * in all three.
 */
export function App() {
  const { snapshot, error, loading, updatedAt } = useFleet(5000);
  const { alerts, connected, retries } = useAlerts();
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const vehicles = snapshot?.vehicles ?? [];
  const selected = useMemo(
    () => vehicles.find((vehicle) => vehicle.vehicleId === selectedId) ?? null,
    [vehicles, selectedId],
  );

  return (
    <div className="app">
      {/* First in the flow, above everything, and not dismissible (FR-6.2). */}
      <DataSourceBanner />

      <header className="topbar">
        <div className="topbar__title">
          <h1>Tessera Risk</h1>
          <p className="muted small">Fleet driver-behaviour risk</p>
        </div>

        <div className="topbar__status">
          {error && <span className="badge badge--warn">API error: {error}</span>}
          {!error && loading && <span className="badge">Loading…</span>}
          {!error && !loading && updatedAt && (
            <span className="badge">Updated {timeAgo(updatedAt)}</span>
          )}
          {snapshot && !snapshot.modelScored && (
            // Named, not blank: the dashboard says why the model column is empty.
            <span className="badge badge--muted">No model trained</span>
          )}
        </div>

        <ul className="legend" aria-label="Risk levels">
          {RISK_ORDER.map((level) => (
            <li key={level}>
              <span className="dot" style={{ background: RISK_COLOURS[level] }} aria-hidden="true" />
              {level.toLowerCase()}
            </li>
          ))}
        </ul>
      </header>

      <main className="layout">
        <section className="layout__map">
          <RiskMap vehicles={vehicles} selectedId={selectedId} onSelect={setSelectedId} />
        </section>

        <aside className="layout__side">
          <FleetTable vehicles={vehicles} selectedId={selectedId} onSelect={setSelectedId} />
          <VehicleDetail vehicle={selected} onClose={() => setSelectedId(null)} />
          <AlertFeed
            alerts={alerts}
            connected={connected}
            retries={retries}
            onSelect={setSelectedId}
          />
        </aside>
      </main>
    </div>
  );
}

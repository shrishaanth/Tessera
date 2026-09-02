import { useState } from "react";
import { useAuth } from "./auth/AuthContext";
import { LoginView } from "./components/LoginView";
import { LiveMapView } from "./components/LiveMapView";
import { DataSourcesView } from "./components/DataSourcesView";

type Tab = "map" | "reports" | "settings";

export function App() {
  const { identity, loading, logout } = useAuth();
  const [tab, setTab] = useState<Tab>("map");

  if (loading) return <div className="login">Loading…</div>;
  if (!identity) return <LoginView />;

  return (
    <div className="app">
      <div className="topbar">
        <span className="brand">TESSERA FLEET</span>
        <span className="spacer" />
        <span className="who">
          {identity.username} · {identity.role}
        </span>
        <button className="link" onClick={() => void logout()}>
          Sign out
        </button>
      </div>
      <div className="body">
        <nav className="nav">
          <button className={tab === "map" ? "active" : ""} onClick={() => setTab("map")}>
            Live Map
          </button>
          <button
            className={tab === "reports" ? "active" : ""}
            onClick={() => setTab("reports")}
            title="Available from Phase 3"
          >
            Reports
          </button>
          <button
            className={tab === "settings" ? "active" : ""}
            onClick={() => setTab("settings")}
          >
            Settings
          </button>
        </nav>
        <main className="view">
          {tab === "map" && <LiveMapView />}
          {tab === "reports" && (
            <div className="ds">
              <h2 className="heading-lg">Reports</h2>
              <p style={{ color: "var(--neutral-500)" }}>
                Historical performance reporting is delivered in Phase 3, once the
                durable layer has collected a sufficient data-collection period
                (SRS §8, FR-4.4).
              </p>
            </div>
          )}
          {tab === "settings" && <DataSourcesView />}
        </main>
      </div>
    </div>
  );
}

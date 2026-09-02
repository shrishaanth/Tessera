import { useState } from "react";
import { useAuth } from "./auth/AuthContext";
import { LiveStreamProvider, useLiveStream } from "./live/LiveStreamContext";
import { LoginView } from "./components/LoginView";
import { LiveMapView } from "./components/LiveMapView";
import { AlertsView } from "./components/AlertsView";
import { ReportsView } from "./components/ReportsView";
import { DataSourcesView } from "./components/DataSourcesView";

type Tab = "map" | "alerts" | "reports" | "settings";

function Shell() {
  const { identity, logout } = useAuth();
  const { connected, unacknowledged } = useLiveStream();
  const [tab, setTab] = useState<Tab>("map");

  return (
    <div className="app">
      <div className="topbar">
        <span className="brand">TESSERA FLEET</span>
        <span
          className="dot"
          title={connected ? "Live stream connected" : "Reconnecting"}
          style={{ background: connected ? "var(--status-available)" : "var(--status-danger)" }}
        />
        <span className="spacer" />
        {unacknowledged > 0 && (
          <button className="link" onClick={() => setTab("alerts")} style={{ color: "var(--status-on-site)" }}>
            ⚠ {unacknowledged} alert{unacknowledged > 1 ? "s" : ""}
          </button>
        )}
        <span className="who">
          {identity!.username} · {identity!.role}
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
          <button className={tab === "alerts" ? "active" : ""} onClick={() => setTab("alerts")}>
            Alerts{unacknowledged > 0 ? ` (${unacknowledged})` : ""}
          </button>
          <button
            className={tab === "reports" ? "active" : ""}
            onClick={() => setTab("reports")}
            title="Available from Phase 3"
          >
            Reports
          </button>
          <button className={tab === "settings" ? "active" : ""} onClick={() => setTab("settings")}>
            Settings
          </button>
        </nav>
        <main className="view">
          {tab === "map" && <LiveMapView />}
          {tab === "alerts" && <AlertsView />}
          {tab === "reports" && <ReportsView />}
          {tab === "settings" && <DataSourcesView />}
        </main>
      </div>
    </div>
  );
}

export function App() {
  const { identity, loading } = useAuth();
  if (loading) return <div className="login">Loading…</div>;
  if (!identity) return <LoginView />;
  return (
    <LiveStreamProvider>
      <Shell />
    </LiveStreamProvider>
  );
}

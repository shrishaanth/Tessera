import { useEffect, useState } from "react";
import { api } from "../api/client";
import type { DataSourceInfo } from "../api/types";

export function DataSourcesView() {
  const [sources, setSources] = useState<DataSourceInfo[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .dataSources()
      .then(setSources)
      .catch(() => setError("Could not load data sources."));
  }, []);

  return (
    <div className="ds">
      <h2 className="heading-lg">Data sources</h2>
      <p style={{ color: "var(--neutral-500)", fontSize: 12.5 }}>
        Every external data source this system depends on, with its real-world
        provenance (FR-7). Sources marked <strong>substitute</strong> stand in for
        a production source that is not yet integrated — they are shown plainly and
        are not production data.
      </p>
      {error && <div style={{ color: "var(--status-danger)" }}>{error}</div>}
      {sources?.map((s) => (
        <div key={s.key} className={`card ${s.role === "SUBSTITUTE" ? "substitute" : ""}`}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "start" }}>
            <div>
              <div className="heading-md">{s.name}</div>
              <div style={{ fontSize: 12, color: "var(--neutral-500)" }}>{s.provider}</div>
            </div>
            <span className={`role ${s.role}`}>{s.role}</span>
          </div>
          <div style={{ fontSize: 12.5, marginTop: 6 }}>{s.purpose}</div>
          {s.disclosure && <div className="disclosure">{s.disclosure}</div>}
        </div>
      ))}
    </div>
  );
}

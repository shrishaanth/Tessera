import { useEffect, useMemo, useState } from "react";
import { api } from "../api/client";
import type { ReportQuery } from "../api/client";
import type { DwellReport, Readiness, ReportFilterOptions, Trend } from "../api/types";

type Preset = 7 | 30 | 90;

function fmtDwell(seconds: number | null): string {
  if (seconds == null) return "—";
  const m = Math.round(seconds / 60);
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)}h ${m % 60}m`;
}

/** Trend chip. `higherIsBetter` decides the good/bad colour; icon + text carry meaning. */
function TrendChip({
  trend,
  higherIsBetter,
  unit,
}: {
  trend: Trend;
  higherIsBetter: boolean;
  unit: string;
}) {
  if (trend.deltaValue == null || trend.direction === "flat") {
    return <span className="trend flat">no change vs previous period</span>;
  }
  const good = higherIsBetter ? trend.direction === "up" : trend.direction === "down";
  const arrow = trend.direction === "up" ? "▲" : "▼";
  const mag = Math.abs(trend.deltaValue);
  return (
    <span className={`trend ${good ? "good" : "bad"}`}>
      {arrow} {mag.toFixed(1)}
      {unit} vs previous period
    </span>
  );
}

export function ReportsView() {
  const [readiness, setReadiness] = useState<Readiness | null>(null);
  const [filters, setFilters] = useState<ReportFilterOptions | null>(null);
  const [dwell, setDwell] = useState<DwellReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [preset, setPreset] = useState<Preset>(30);
  const [siteId, setSiteId] = useState("");

  const query = useMemo<ReportQuery>(() => {
    const to = Date.now();
    const from = to - preset * 86_400_000;
    return { from, to, siteId: siteId || undefined };
  }, [preset, siteId]);

  useEffect(() => {
    api.reportReadiness().then(setReadiness).catch(() => {});
    api.reportFilters().then(setFilters).catch(() => {});
  }, []);

  useEffect(() => {
    setError(null);
    api
      .dwellReport(query)
      .then(setDwell)
      .catch(() => setError("Could not load the report."));
  }, [query]);

  const maxDwell = Math.max(
    1,
    ...(dwell?.bySite.map((s) => s.avgDwellSeconds ?? 0) ?? [1]),
  );

  return (
    <div className="ds reports">
      <h2 className="heading-lg">Site dwell time</h2>

      {readiness && !readiness.ready && (
        <div className="report-banner warn">
          <strong>These figures are not yet reliable.</strong> Reporting needs a
          minimum data-collection period and enough recorded site visits before it
          can be trusted (FR-4.4).
          <ul>
            {readiness.reasons.map((r, i) => (
              <li key={i}>{r}</li>
            ))}
          </ul>
        </div>
      )}
      {readiness?.syntheticHistory && (
        <div className="report-banner info">
          Demo mode: the history below includes <strong>synthetic back-filled data</strong>,
          not real operations.
        </div>
      )}

      <div className="report-filters">
        <div className="seg">
          {([7, 30, 90] as Preset[]).map((p) => (
            <button
              key={p}
              className={preset === p ? "on" : ""}
              onClick={() => setPreset(p)}
            >
              {p}d
            </button>
          ))}
        </div>
        <select value={siteId} onChange={(e) => setSiteId(e.target.value)} aria-label="Site">
          <option value="">All sites</option>
          {filters?.sites.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
      </div>

      {error && <div className="report-banner warn">{error}</div>}

      <div className="kpi-row">
        <div className="kpi">
          <div className="label-xs">Average dwell time</div>
          <div className="stat" style={{ color: "var(--status-on-site)" }}>
            {fmtDwell(dwell?.overallAvgDwellSeconds ?? null)}
          </div>
          <div className="kpi-sub">{dwell ? `${dwell.totalVisits} site visits` : "…"}</div>
          {dwell && <TrendChip trend={dwell.trend} higherIsBetter={false} unit="s" />}
        </div>
      </div>

      <section className="report-block">
        <div className="heading-md">Average dwell by site</div>
        <table className="report-table">
          <thead>
            <tr>
              <th>Site</th>
              <th>Avg dwell</th>
              <th style={{ textAlign: "right" }}>Visits</th>
            </tr>
          </thead>
          <tbody>
            {dwell?.bySite.length === 0 && (
              <tr>
                <td colSpan={3} className="muted">
                  No site visits in this period.
                </td>
              </tr>
            )}
            {dwell?.bySite.map((s) => (
              <tr key={s.siteId}>
                <td>{s.siteName}</td>
                <td>
                  <span className="bartrack inline">
                    <span
                      className="barfill"
                      style={{
                        width: `${((s.avgDwellSeconds ?? 0) / maxDwell) * 100}%`,
                        background: "var(--status-on-site)",
                      }}
                    />
                  </span>
                  <span className="mono">{fmtDwell(s.avgDwellSeconds)}</span>
                  {!s.enoughData && <span className="tag">insufficient data</span>}
                </td>
                <td style={{ textAlign: "right" }} className="mono">
                  {s.visits}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}

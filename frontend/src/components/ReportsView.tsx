import { useEffect, useMemo, useState } from "react";
import { api } from "../api/client";
import type { ReportQuery } from "../api/client";
import type {
  DwellReport,
  OnTimeReport,
  Readiness,
  ReportFilterOptions,
  Trend,
} from "../api/types";

type Preset = 7 | 30 | 90;

function fmtPct(v: number | null): string {
  return v == null ? "—" : `${v.toFixed(1)}%`;
}

function fmtDwell(seconds: number | null): string {
  if (seconds == null) return "—";
  const m = Math.round(seconds / 60);
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)}h ${m % 60}m`;
}

function weekLabel(ms: number): string {
  return new Date(ms).toLocaleDateString([], { month: "short", day: "numeric" });
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
  const [onTime, setOnTime] = useState<OnTimeReport | null>(null);
  const [dwell, setDwell] = useState<DwellReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [preset, setPreset] = useState<Preset>(30);
  const [route, setRoute] = useState("");
  const [driver, setDriver] = useState("");
  const [siteId, setSiteId] = useState("");

  const query = useMemo<ReportQuery>(() => {
    const to = Date.now();
    const from = to - preset * 86_400_000;
    return {
      from,
      to,
      route: route || undefined,
      driver: driver || undefined,
      siteId: siteId || undefined,
    };
  }, [preset, route, driver, siteId]);

  useEffect(() => {
    api.reportReadiness().then(setReadiness).catch(() => {});
    api.reportFilters().then(setFilters).catch(() => {});
  }, []);

  useEffect(() => {
    setError(null);
    Promise.all([api.onTimeReport(query), api.dwellReport(query)])
      .then(([o, d]) => {
        setOnTime(o);
        setDwell(d);
      })
      .catch(() => setError("Could not load reports."));
  }, [query]);

  const maxWeekPct = Math.max(
    1,
    ...(onTime?.byWeek.map((w) => w.onTimePct ?? 0) ?? [1]),
  );
  const maxDwell = Math.max(
    1,
    ...(dwell?.bySite.map((s) => s.avgDwellSeconds ?? 0) ?? [1]),
  );

  return (
    <div className="ds reports">
      <h2 className="heading-lg">Performance</h2>

      {readiness && !readiness.ready && (
        <div className="report-banner warn">
          <strong>These figures are not yet reliable.</strong> Historical reporting
          needs a minimum data-collection period before it can be trusted (FR-4.4).
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
        <select value={route} onChange={(e) => setRoute(e.target.value)} aria-label="Route">
          <option value="">All routes</option>
          {filters?.routes.map((r) => (
            <option key={r} value={r}>
              {r}
            </option>
          ))}
        </select>
        <select value={driver} onChange={(e) => setDriver(e.target.value)} aria-label="Driver">
          <option value="">All drivers</option>
          {filters?.drivers.map((d) => (
            <option key={d} value={d}>
              {d}
            </option>
          ))}
        </select>
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
          <div className="label-xs">On-time arrival</div>
          <div className="stat" style={{ color: "var(--brand-primary)" }}>
            {fmtPct(onTime?.onTimePct ?? null)}
          </div>
          <div className="kpi-sub">
            {onTime ? `${onTime.onTime} / ${onTime.completed} jobs` : "…"}
          </div>
          {onTime && (
            <TrendChip trend={onTime.trend} higherIsBetter unit=" pts" />
          )}
        </div>
        <div className="kpi">
          <div className="label-xs">Average dwell time</div>
          <div className="stat" style={{ color: "var(--status-on-site)" }}>
            {fmtDwell(dwell?.overallAvgDwellSeconds ?? null)}
          </div>
          <div className="kpi-sub">{dwell ? `${dwell.totalVisits} site visits` : "…"}</div>
          {dwell && (
            <TrendChip trend={dwell.trend} higherIsBetter={false} unit="s" />
          )}
        </div>
      </div>

      <section className="report-block">
        <div className="heading-md">On-time % by week</div>
        {onTime && onTime.byWeek.length === 0 && (
          <div className="muted">No completed jobs in this period.</div>
        )}
        <div className="barlist" role="table" aria-label="On-time percentage by week">
          {onTime?.byWeek.map((w) => (
            <div
              className="barrow"
              role="row"
              key={w.weekStartEpochMs}
              title={`Week of ${weekLabel(w.weekStartEpochMs)}: ${w.onTime}/${w.completed} on time`}
            >
              <span className="barrow-label">{weekLabel(w.weekStartEpochMs)}</span>
              <span className="bartrack">
                <span
                  className="barfill"
                  style={{
                    width: `${((w.onTimePct ?? 0) / maxWeekPct) * 100}%`,
                    background: "var(--brand-primary)",
                  }}
                />
              </span>
              <span className="barrow-value mono">{fmtPct(w.onTimePct)}</span>
              <span className="barrow-note">{w.completed} jobs</span>
            </div>
          ))}
        </div>
      </section>

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

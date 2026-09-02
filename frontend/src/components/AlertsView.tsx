import { useMemo, useState } from "react";
import { useLiveStream } from "../live/LiveStreamContext";

function clock(ms: number): string {
  return new Date(ms).toLocaleString([], {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function AlertsView() {
  const { alerts, ackAlert } = useLiveStream();
  const [showAcked, setShowAcked] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);

  const visible = useMemo(
    () =>
      [...alerts]
        .filter((a) => showAcked || !a.acknowledged)
        .sort((a, b) => b.createdAtEpochMs - a.createdAtEpochMs),
    [alerts, showAcked],
  );

  const ack = async (id: string) => {
    setBusy(id);
    try {
      await ackAlert(id);
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="ds">
      <div style={{ display: "flex", alignItems: "baseline", justifyContent: "space-between" }}>
        <h2 className="heading-lg">Alerts &amp; exceptions</h2>
        <label style={{ fontSize: 12, color: "var(--neutral-500)" }}>
          <input
            type="checkbox"
            checked={showAcked}
            onChange={(e) => setShowAcked(e.target.checked)}
          />{" "}
          show acknowledged
        </label>
      </div>
      <p style={{ color: "var(--neutral-500)", fontSize: 12.5 }}>
        Dwell-time exceptions raised when a vehicle stays inside a customer site
        longer than its threshold (FR-3.5).
      </p>

      {visible.length === 0 && (
        <div className="card" style={{ color: "var(--neutral-500)" }}>
          No alerts.
        </div>
      )}

      {visible.map((a) => (
        <div
          key={a.id}
          className="card"
          style={{
            borderLeft: `4px solid ${
              a.severity === "WARNING" ? "var(--status-on-site)" : "var(--brand-primary)"
            }`,
            opacity: a.acknowledged ? 0.55 : 1,
          }}
        >
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <div>
              <div className="heading-md">{a.message}</div>
              <div style={{ fontSize: 11, color: "var(--neutral-500)" }}>
                {a.type} · {clock(a.createdAtEpochMs)}
                {a.vehicleId ? ` · ${a.vehicleId}` : ""}
              </div>
            </div>
            {!a.acknowledged ? (
              <button className="btn" disabled={busy === a.id} onClick={() => void ack(a.id)}>
                {busy === a.id ? "…" : "Acknowledge"}
              </button>
            ) : (
              <span className="label-xs">acknowledged</span>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}

import { useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import type { GeocodeResponse, GeocodeResult, SiteView } from "../api/types";

export interface PickedLocation {
  lat: number;
  lon: number;
  label: string;
  kind: "address" | "site";
}

interface Props {
  onPick: (loc: PickedLocation) => void;
  placeholder?: string;
}

/**
 * Address autocomplete + fuzzy customer-site search for new-job entry
 * (FR-6.1, FR-6.3). Debounced; a picked result carries real coordinates so the
 * nearest-vehicle search can run straight away (FR-6.2).
 */
export function AddressSearchBox({ onPick, placeholder }: Props) {
  const [q, setQ] = useState("");
  const [addresses, setAddresses] = useState<GeocodeResult[]>([]);
  const [sites, setSites] = useState<SiteView[]>([]);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [degraded, setDegraded] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout>>();

  useEffect(() => {
    clearTimeout(timer.current);
    const term = q.trim();
    if (term.length < 3) {
      setAddresses([]);
      setSites([]);
      return;
    }
    timer.current = setTimeout(async () => {
      setBusy(true);
      try {
        const geoFallback: GeocodeResponse = { query: term, results: [], degraded: true };
        const [geo, siteHits] = await Promise.all([
          api.geocode(term).catch(() => geoFallback),
          api.searchSites(term).catch((): SiteView[] => []),
        ]);
        setAddresses(geo.results);
        setDegraded(geo.degraded);
        setSites(siteHits);
        setOpen(true);
      } finally {
        setBusy(false);
      }
    }, 400);
    return () => clearTimeout(timer.current);
  }, [q]);

  const pick = (loc: PickedLocation) => {
    onPick(loc);
    setQ(loc.label);
    setOpen(false);
  };

  const hasResults = addresses.length > 0 || sites.length > 0;

  return (
    <div className="searchbox">
      <input
        value={q}
        onChange={(e) => setQ(e.target.value)}
        onFocus={() => hasResults && setOpen(true)}
        onKeyDown={(e) => e.key === "Escape" && setOpen(false)}
        placeholder={placeholder ?? "Search address or customer site…"}
        aria-label="Search address or customer site"
      />
      {open && (q.trim().length >= 3) && (
        <div className="searchbox-menu" role="listbox">
          {busy && <div className="searchbox-note">Searching…</div>}
          {!busy && !hasResults && (
            <div className="searchbox-note">
              {degraded ? "Address lookup unavailable — pick a point on the map." : "No matches."}
            </div>
          )}
          {sites.length > 0 && (
            <>
              <div className="searchbox-group">Customer sites</div>
              {sites.map((s) => (
                <button
                  key={s.id}
                  className="searchbox-item"
                  role="option"
                  onClick={() =>
                    pick({
                      lat: s.centerLat ?? s.outline[0]?.[0] ?? 0,
                      lon: s.centerLon ?? s.outline[0]?.[1] ?? 0,
                      label: s.name,
                      kind: "site",
                    })
                  }
                >
                  <span className="dot" style={{ background: "var(--status-on-site)" }} /> {s.name}
                  {s.address ? <span className="searchbox-sub"> · {s.address}</span> : null}
                </button>
              ))}
            </>
          )}
          {addresses.length > 0 && (
            <>
              <div className="searchbox-group">Addresses</div>
              {addresses.map((a, i) => (
                <button
                  key={i}
                  className="searchbox-item"
                  role="option"
                  onClick={() =>
                    pick({ lat: a.latitude, lon: a.longitude, label: a.displayName, kind: "address" })
                  }
                >
                  {a.displayName}
                </button>
              ))}
            </>
          )}
        </div>
      )}
    </div>
  );
}

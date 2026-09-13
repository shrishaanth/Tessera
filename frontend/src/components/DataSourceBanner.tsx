import { useEffect, useState } from "react";

import { api } from "../api/client";
import type { DataSourceInfo } from "../api/types";

/**
 * States plainly that the data is simulated (FR-6.1, FR-6.2).
 *
 * <h2>Why this is not a footnote</h2>
 * FR-6.2 requires the disclosure not be visually minimised, and this is the part of
 * the interface where that is either honoured or quietly broken. So it sits at the
 * top of the page in the normal flow — not in a corner, not behind a tooltip, not
 * in small grey text under the fold — and it is not dismissible. A banner an
 * operator can close is a banner most operators have closed.
 *
 * <p>The detail is collapsed by default only because there are five paragraphs of
 * it; the headline, which is the part that matters, is always visible. Collapsing
 * the explanation is different from hiding the claim.
 *
 * <h2>Why the text comes from the API</h2>
 * Hard-coding it here would let the disclosure drift out of step with the system
 * that generates the data — the text would still say "simulated" long after someone
 * changed how. Serving it next to the data means one source of truth. If the
 * request fails, a fallback statement is shown rather than nothing: a page that
 * silently omits the disclosure because an endpoint was down is the exact failure
 * FR-6.1 exists to prevent.
 */
export function DataSourceBanner() {
  const [info, setInfo] = useState<DataSourceInfo | null>(null);
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    api
      .dataSource(controller.signal)
      .then(setInfo)
      .catch(() => setInfo(null));
    return () => controller.abort();
  }, []);

  const headline = info?.headline ?? "All vehicle data on this dashboard is simulated.";
  const details = info?.details ?? [
    "The reporting service could not be reached for the full description, but the "
      + "data shown is generated, not collected. No physical vehicles, telematics "
      + "feed, or personal data are involved.",
  ];

  return (
    <section className="disclosure" aria-label="Data source">
      <div className="disclosure__headline">
        <span className="disclosure__badge">Simulated data</span>
        <p>{headline}</p>
        <button
          type="button"
          className="disclosure__toggle"
          onClick={() => setExpanded((open) => !open)}
          aria-expanded={expanded}
        >
          {expanded ? "Hide detail" : "How this data is generated"}
        </button>
      </div>
      {expanded && (
        <ul className="disclosure__details">
          {details.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      )}
    </section>
  );
}

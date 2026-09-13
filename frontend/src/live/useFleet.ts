import { useCallback, useEffect, useRef, useState } from "react";

import { api } from "../api/client";
import type { FleetSnapshot } from "../api/types";

export interface FleetState {
  snapshot: FleetSnapshot | null;
  /** The last error, kept alongside the last good snapshot rather than replacing it. */
  error: string | null;
  loading: boolean;
  /** When the last successful poll landed, for the staleness indicator. */
  updatedAt: number | null;
  refresh: () => void;
}

/**
 * Polls the fleet snapshot (FR-5.2, "refreshed continuously").
 *
 * <h2>Why polling rather than a socket</h2>
 * The state changes once per micro-batch — every ten seconds — and a poll that
 * returns the whole fleet is a few kilobytes. A second WebSocket would add a
 * reconnect path and a partial-update protocol to save a request every five
 * seconds. The alert feed is pushed because alerts are events that must arrive the
 * moment they happen; fleet state is a snapshot, and asking for it is fine.
 *
 * <h2>Why the last good snapshot survives an error</h2>
 * A failed poll blanks the map if the snapshot is cleared, so a momentary blip
 * would empty the screen and then repopulate it. Keeping the last good data and
 * surfacing the error separately means the operator sees stale-but-labelled data
 * rather than nothing at all.
 */
export function useFleet(intervalMs = 5000): FleetState {
  const [snapshot, setSnapshot] = useState<FleetSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);

  /** Guards against overlapping polls when the API is slower than the interval. */
  const inFlight = useRef(false);
  const [tick, setTick] = useState(0);

  const refresh = useCallback(() => setTick((value) => value + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let cancelled = false;

    const poll = async () => {
      if (inFlight.current) {
        return;
      }
      inFlight.current = true;
      try {
        const next = await api.fleet(controller.signal);
        if (!cancelled) {
          setSnapshot(next);
          setError(null);
          setUpdatedAt(Date.now());
        }
      } catch (cause) {
        // AbortError is this effect tearing down, not a failure worth showing.
        if (!cancelled && (cause as Error)?.name !== "AbortError") {
          setError((cause as Error).message);
        }
      } finally {
        inFlight.current = false;
        if (!cancelled) {
          setLoading(false);
          timer = setTimeout(poll, intervalMs);
        }
      }
    };

    void poll();
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) {
        clearTimeout(timer);
      }
    };
  }, [intervalMs, tick]);

  return { snapshot, error, loading, updatedAt, refresh };
}

import { useEffect, useRef, useState } from "react";

import { alertSocketUrl } from "../api/client";
import type { RiskAlert } from "../api/types";

export interface AlertStreamState {
  alerts: RiskAlert[];
  connected: boolean;
  /** Reconnect attempts since the last successful connection, for the status line. */
  retries: number;
}

/** Alerts retained in the browser. The server keeps its own bounded history. */
const MAX_ALERTS = 200;

/** Reconnect backoff, capped so a long outage does not stretch to minutes. */
const BASE_DELAY_MS = 1000;
const MAX_DELAY_MS = 15000;

/**
 * Subscribes to the alert feed (FR-5.3).
 *
 * <h2>Reconnection is the whole job</h2>
 * A WebSocket that is opened once and never re-opened looks like a working feed
 * that silently stopped: the page keeps rendering the alerts it already had, and an
 * operator has no way to tell a calm fleet from a dead socket. So the connection is
 * re-established with exponential backoff, and `connected` is surfaced to the UI so
 * the distinction is visible rather than assumed.
 *
 * <p>No history is fetched on connect. The server replays its recent buffer to
 * every new client, so a page opened after an alert was raised still shows it, and
 * asking over HTTP as well would duplicate them.
 */
export function useAlerts(): AlertStreamState {
  const [alerts, setAlerts] = useState<RiskAlert[]>([]);
  const [connected, setConnected] = useState(false);
  const [retries, setRetries] = useState(0);

  /** Held in a ref so the effect can tear down without re-running on every alert. */
  const socketRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    let closed = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let attempt = 0;

    const connect = () => {
      if (closed) {
        return;
      }
      const socket = new WebSocket(alertSocketUrl());
      socketRef.current = socket;

      socket.onopen = () => {
        if (closed) {
          return;
        }
        attempt = 0;
        setConnected(true);
        setRetries(0);
      };

      socket.onmessage = (event) => {
        try {
          const alert = JSON.parse(event.data as string) as RiskAlert;
          setAlerts((current) => {
            // Newest first, and capped: the feed is a live view, not an archive,
            // and an unbounded list would grow for as long as the tab is open.
            const next = [alert, ...current];
            return next.length > MAX_ALERTS ? next.slice(0, MAX_ALERTS) : next;
          });
        } catch {
          // One unparseable frame must not kill the feed.
        }
      };

      socket.onclose = () => {
        if (closed) {
          return;
        }
        setConnected(false);
        attempt += 1;
        setRetries(attempt);
        const delay = Math.min(BASE_DELAY_MS * 2 ** (attempt - 1), MAX_DELAY_MS);
        timer = setTimeout(connect, delay);
      };

      // onerror is followed by onclose, which owns the retry; closing here as well
      // would schedule two reconnects for one failure.
      socket.onerror = () => socket.close();
    };

    connect();
    return () => {
      closed = true;
      if (timer) {
        clearTimeout(timer);
      }
      socketRef.current?.close();
    };
  }, []);

  return { alerts, connected, retries };
}

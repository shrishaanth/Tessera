import { useEffect, useRef, useState } from "react";
import type { FleetFrame, Vehicle } from "../api/types";

export interface LiveFleet {
  vehicles: Vehicle[];
  connected: boolean;
  lastUpdateMs: number | null;
}

/**
 * Subscribes to the live fleet WebSocket (`/ws/live`) and keeps the latest
 * snapshot. Reconnects with backoff. The frame cadence is ~1s (backend), well
 * inside the 2-second freshness bound (FR-1.2).
 */
export function useLiveFleet(enabled: boolean): LiveFleet {
  const [vehicles, setVehicles] = useState<Vehicle[]>([]);
  const [connected, setConnected] = useState(false);
  const [lastUpdateMs, setLastUpdateMs] = useState<number | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const retryRef = useRef(0);

  useEffect(() => {
    if (!enabled) return;
    let closed = false;
    let reconnectTimer: ReturnType<typeof setTimeout>;

    const connect = () => {
      const proto = window.location.protocol === "https:" ? "wss" : "ws";
      const ws = new WebSocket(`${proto}://${window.location.host}/ws/live`);
      wsRef.current = ws;

      ws.onopen = () => {
        retryRef.current = 0;
        setConnected(true);
      };
      ws.onmessage = (evt) => {
        try {
          const frame = JSON.parse(evt.data) as FleetFrame;
          if (frame.type === "fleet") {
            setVehicles(frame.vehicles);
            setLastUpdateMs(frame.ts ?? Date.now());
          }
        } catch {
          /* ignore malformed frame */
        }
      };
      ws.onclose = () => {
        setConnected(false);
        if (closed) return;
        const delay = Math.min(1000 * 2 ** retryRef.current++, 10000);
        reconnectTimer = setTimeout(connect, delay);
      };
      ws.onerror = () => ws.close();
    };

    connect();
    return () => {
      closed = true;
      clearTimeout(reconnectTimer);
      wsRef.current?.close();
    };
  }, [enabled]);

  return { vehicles, connected, lastUpdateMs };
}

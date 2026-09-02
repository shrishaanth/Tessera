import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import type { ReactNode } from "react";
import { api } from "../api/client";
import type { Alert, GeofenceEventFrameData, LiveFrame, Vehicle } from "../api/types";

export interface GeofenceFeedItem extends GeofenceEventFrameData {
  receivedAt: number;
}

interface LiveStream {
  vehicles: Vehicle[];
  connected: boolean;
  lastUpdateMs: number | null;
  geofenceFeed: GeofenceFeedItem[];
  alerts: Alert[];
  unacknowledged: number;
  ackAlert: (id: string) => Promise<void>;
}

const Ctx = createContext<LiveStream | undefined>(undefined);

const MAX_FEED = 60;

export function LiveStreamProvider({ children }: { children: ReactNode }) {
  const [vehicles, setVehicles] = useState<Vehicle[]>([]);
  const [connected, setConnected] = useState(false);
  const [lastUpdateMs, setLastUpdateMs] = useState<number | null>(null);
  const [geofenceFeed, setGeofenceFeed] = useState<GeofenceFeedItem[]>([]);
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const retry = useRef(0);

  // Seed alerts from REST once, then keep them current from the socket.
  useEffect(() => {
    api
      .alerts(true)
      .then((a) => setAlerts(a))
      .catch(() => {});
  }, []);

  useEffect(() => {
    let closed = false;
    let timer: ReturnType<typeof setTimeout>;
    let ws: WebSocket | null = null;

    const connect = () => {
      const proto = window.location.protocol === "https:" ? "wss" : "ws";
      ws = new WebSocket(`${proto}://${window.location.host}/ws/live`);

      ws.onopen = () => {
        retry.current = 0;
        setConnected(true);
      };
      ws.onmessage = (evt) => {
        let frame: LiveFrame;
        try {
          frame = JSON.parse(evt.data) as LiveFrame;
        } catch {
          return;
        }
        if (frame.type === "fleet") {
          setVehicles(frame.vehicles);
          setLastUpdateMs(frame.ts ?? Date.now());
        } else if (frame.type === "geofence") {
          setGeofenceFeed((prev) =>
            [{ ...frame.event, receivedAt: frame.ts ?? Date.now() }, ...prev].slice(0, MAX_FEED),
          );
        } else if (frame.type === "alert") {
          setAlerts((prev) => {
            if (prev.some((a) => a.id === frame.alert.id)) return prev;
            return [frame.alert, ...prev];
          });
        }
      };
      ws.onclose = () => {
        setConnected(false);
        if (closed) return;
        timer = setTimeout(connect, Math.min(1000 * 2 ** retry.current++, 10000));
      };
      ws.onerror = () => ws?.close();
    };

    connect();
    return () => {
      closed = true;
      clearTimeout(timer);
      ws?.close();
    };
  }, []);

  const ackAlert = useCallback(async (id: string) => {
    const updated = await api.ackAlert(id);
    setAlerts((prev) => prev.map((a) => (a.id === id ? updated : a)));
  }, []);

  const value = useMemo<LiveStream>(
    () => ({
      vehicles,
      connected,
      lastUpdateMs,
      geofenceFeed,
      alerts,
      unacknowledged: alerts.filter((a) => !a.acknowledged).length,
      ackAlert,
    }),
    [vehicles, connected, lastUpdateMs, geofenceFeed, alerts, ackAlert],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useLiveStream(): LiveStream {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useLiveStream must be used within LiveStreamProvider");
  return ctx;
}

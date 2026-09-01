import { useEffect, useState } from 'react';
import { WS_BASE_URL } from '../constants/config';

interface LiveFeedState {
  ltp: number | null;
  connected: boolean;
  error: string | null;
}

/** Subscribes to one instrument's live price via the backend's WebSocket relay
 * (/ws/live-feed — never talks to Upstox directly). Reconnects automatically on drop;
 * re-subscribes automatically when `instrument` changes. Ticks only flow during real market
 * hours — outside those, expect `connected: true` with `ltp` staying null indefinitely, which is
 * normal, not an error. */
export function useLiveFeed(instrument: string | null): LiveFeedState {
  const [ltp, setLtp] = useState<number | null>(null);
  const [connected, setConnected] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setLtp(null);
    if (!instrument) {
      setConnected(false);
      setError(null);
      return;
    }

    let cancelled = false;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let ws: WebSocket | null = null;

    const connect = () => {
      if (cancelled) return;
      ws = new WebSocket(`${WS_BASE_URL}/ws/live-feed`);

      ws.onopen = () => {
        if (cancelled || !ws) return;
        setConnected(true);
        setError(null);
        ws.send(JSON.stringify({ action: 'subscribe', instrument }));
      };

      ws.onmessage = (event) => {
        if (cancelled) return;
        try {
          const data = JSON.parse(event.data as string);
          if (data.error) {
            setError(String(data.error));
          } else if (data.instrument === instrument && typeof data.ltp === 'number') {
            setLtp(data.ltp);
          }
        } catch {
          // ignore malformed frames
        }
      };

      ws.onerror = () => {
        if (cancelled) return;
        setError('Live feed connection error');
      };

      ws.onclose = () => {
        if (cancelled) return;
        setConnected(false);
        reconnectTimer = setTimeout(connect, 5000);
      };
    };

    connect();

    return () => {
      cancelled = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action: 'unsubscribe' }));
      }
      ws?.close();
    };
  }, [instrument]);

  return { ltp, connected, error };
}

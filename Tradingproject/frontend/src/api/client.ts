import { API_BASE_URL, Instrument } from '../constants/config';
import type {
  BacktestResult,
  BasketSnapshot,
  DailyTrajectory,
  HealthStatus,
  IntradayReanchorBacktestResult,
  Prediction,
  RangeCalibrationBacktestResult,
  RangeCalibrationStatus,
  SignalHistoryEntry,
} from './types';

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`);
  if (!response.ok) {
    throw new Error(`Request failed (${response.status}): ${path}`);
  }
  return response.json() as Promise<T>;
}

export const api = {
  getHealth: () => getJson<HealthStatus>('/actuator/health'),

  getForecastHistory: (instrument: Instrument, interval: '1h' | '1d') =>
    getJson<Prediction[]>(`/api/forecast/${instrument}/history?interval=${interval}`),

  /** Undefined date = today (IST), matching the backend's default. Returns null if no prediction exists yet for that day. */
  getTrajectory: (instrument: Instrument, date?: string) =>
    getJson<DailyTrajectory | null>(
      `/api/forecast/${instrument}/daily/trajectory${date ? `?date=${date}` : ''}`
    ),

  getSignalHistory: (instrument: Instrument) =>
    getJson<SignalHistoryEntry[]>(`/api/signals/${instrument}/history`),

  getRangeCalibration: (instrument: Instrument, interval: '1h' | '1d') =>
    getJson<RangeCalibrationStatus>(
      `/api/forecast/${instrument}/range-calibration?interval=${interval}`
    ),

  getLoginUrl: () => getJson<{ loginUrl: string }>('/auth/upstox/login-url'),

  // Analysis — all recomputed fresh from currently-stored history on every call, so results
  // genuinely reflect however much real data has accumulated so far.
  getModelBacktest: (instrument: Instrument) =>
    getJson<BacktestResult[]>(`/api/forecast/${instrument}/backtest?interval=1h`),

  getDailyModelBacktest: (instrument: Instrument) =>
    getJson<BacktestResult[]>(`/api/forecast/${instrument}/daily-backtest`),

  getBiasCorrectionBacktest: (instrument: Instrument) =>
    getJson<BacktestResult[]>(`/api/forecast/${instrument}/bias-correction-backtest?interval=1h`),

  getDailyBiasCorrectionBacktest: (instrument: Instrument) =>
    getJson<BacktestResult[]>(`/api/forecast/${instrument}/daily-bias-correction-backtest`),

  getRangeCalibrationBacktest: (instrument: Instrument) =>
    getJson<RangeCalibrationBacktestResult[]>(
      `/api/forecast/${instrument}/range-calibration-backtest?interval=1h`
    ),

  getDailyRangeCalibrationBacktest: (instrument: Instrument) =>
    getJson<RangeCalibrationBacktestResult[]>(
      `/api/forecast/${instrument}/daily-range-calibration-backtest`
    ),

  getIntradayReanchorBacktest: (instrument: Instrument) =>
    getJson<IntradayReanchorBacktestResult>(`/api/forecast/${instrument}/intraday-reanchor-backtest`),

  // Phase D — live monitoring across NIFTY, BANKNIFTY, and the NIFTY 50 basket. No signals/confidence, just current state.
  getBasketSnapshot: () => getJson<BasketSnapshot[]>('/api/monitor/basket'),
};

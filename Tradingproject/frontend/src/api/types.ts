/** Mirrors the backend's HourlyPrediction entity — used for both the hourly loop (interval "1h") and the same-day close prediction (interval "1d"). */
export interface Prediction {
  id: number;
  instrument: string;
  interval: string;
  modelName: string;
  predictedAtTs: string;
  predictedForTs: string;
  predictedClose: number;
  rangeLow: number;
  rangeHigh: number;
  biasCorrectionApplied: number | null;
  actualClose: number | null;
  errorPct: number | null;
  evaluatedAt: string | null;
}

export interface TrajectoryPoint {
  ts: string;
  actualClose: number;
  deviationFromPrediction: number;
  deviationPct: number;
  withinPredictedRange: boolean;
}

export interface DailyTrajectory {
  instrument: string;
  date: string;
  predictedClose: number;
  rangeLow: number;
  rangeHigh: number;
  actualClose: number | null;
  points: TrajectoryPoint[];
  /** Live, continuously re-anchored estimate from the latest known price — null once the day is fully evaluated. */
  currentEstimatedClose: number | null;
  currentEstimatedRangeLow: number | null;
  currentEstimatedRangeHigh: number | null;
}

export interface SignalHistoryEntry {
  id: number;
  instrument: string;
  ts: string;
  patternId: string;
  predictedDirection: 'up' | 'down';
  confidenceTier: 'low' | 'medium' | 'high';
  sampleSize: number;
  actualDirection: 'up' | 'down' | null;
  actualMovePct: number | null;
}

export interface RangeCalibrationStatus {
  instrument: string;
  interval: string;
  multiplier: number;
  updatedAt: string | null;
}

export interface HealthStatus {
  status: 'UP' | 'DOWN' | string;
  groups?: string[];
}

/** A named strategy's aggregate walk-forward performance — used for both model and bias-correction comparisons. */
export interface BacktestResult {
  modelName: string;
  sampleSize: number;
  meanAbsoluteErrorPct: number | null;
  pctActualWithinPredictedRange: number | null;
  avgRangeWidthPct: number | null;
}

/** Same as BacktestResult, plus the multiplier the online range calibrator converged to. */
export interface RangeCalibrationBacktestResult extends BacktestResult {
  finalMultiplier: number | null;
}

export interface HourBucketComparison {
  hoursSinceOpen: number;
  sampleSize: number;
  staticMeanAbsErrorPct: number | null;
  staticCoveragePct: number | null;
  reanchoredMeanAbsErrorPct: number | null;
  reanchoredCoveragePct: number | null;
  reanchoredAvgRangeWidthPct: number | null;
}

export interface BigMorningMissComparison {
  sampleSize: number;
  staticMeanAbsErrorPct: number | null;
  staticCoveragePct: number | null;
  reanchoredMeanAbsErrorPct: number | null;
  reanchoredCoveragePct: number | null;
}

export interface IntradayReanchorBacktestResult {
  byHour: HourBucketComparison[];
  bigMorningMiss: BigMorningMissComparison;
}

/** Phase D monitoring snapshot — NIFTY, BANKNIFTY, and the NIFTY 50 basket. No pattern-signal confidence attached. */
export interface BasketSnapshot {
  symbol: string;
  lastCandleTs: string;
  lastClose: number;
  changePct: number;
  ema9: number | null;
  ema21: number | null;
  trend: 'BULLISH' | 'BEARISH' | 'NEUTRAL' | 'UNKNOWN';
  rsi14: number | null;
  rsiZone: 'OVERBOUGHT' | 'OVERSOLD' | 'NEUTRAL' | 'UNKNOWN';
  candleCount: number;
  /** Today's same-day close prediction, once the day's first candle exists — null before then. */
  predictedClose: number | null;
  predictedRangeLow: number | null;
  predictedRangeHigh: number | null;
  /** How far today's price has moved from the morning's predicted close, as a %. Null with no prediction yet. */
  deviationFromPredictionPct: number | null;
}

/** One model's prediction for a given day, normalized so DETERMINISTIC and AI rows render in the
 * same table. rangeLow/rangeHigh: DETERMINISTIC's calibrated range, or AI's historical-error band
 * (null until it has enough evaluated history to compute one honestly). baselinePrice only
 * applies to AI. currentPrice is the latest real price for this day regardless of whether this
 * row has been formally scored yet (evaluated) — use it to show a live number instead of a blank
 * "pending" state. */
export interface PredictionComparisonRow {
  source: 'DETERMINISTIC' | 'AI';
  label: string;
  modelName: string;
  targetDate: string;
  predictedPrice: number | null;
  rangeLow: number | null;
  rangeHigh: number | null;
  baselinePrice: number | null;
  currentPrice: number | null;
  actualPrice: number | null;
  evaluated: boolean;
  betterThanBaseline: boolean | null;
  directionCorrect: boolean | null;
}

export interface PredictionComparisonResponse {
  instrument: string;
  date: string;
  rows: PredictionComparisonRow[];
}

/** Head-to-head accuracy between the two live models over their most recent shared evaluated
 * days for one instrument — real recorded outcomes, not a live-updating score. sampleSize grows
 * slowly since both ledgers are young; treat small samples as not yet meaningful. */
export interface ModelLeaderboard {
  instrument: string;
  sampleSize: number;
  deterministicWins: number;
  aiWins: number;
  ties: number;
}

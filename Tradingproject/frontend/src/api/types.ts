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

/** One individual AI call, never overwritten by a later one for the same day — the fair way to
 * see "what did the AI say an hour ago" instead of only its latest, continuously-refreshed value. */
export interface AiPredictionSnapshot {
  predictedAtTs: string;
  targetDate: string;
  predictedValue: number;
  predictedRangeLow: number | null;
  predictedRangeHigh: number | null;
  sequenceInDay: number;
  afterClose: boolean;
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

/** One financial ratio's current known value for a basket stock (P/E, P/B, ROA, ROE, ROCE,
 * EV/EBITDA, Quick Ratio), with a sector benchmark alongside it — from Upstox's own fundamentals
 * API. Values come through as strings (some are plain numbers, some carry a "%") since that's
 * the shape the source gives them in. Only ever populated for basket stocks — NIFTY/BANKNIFTY
 * are indices, not companies. */
export interface CompanyFundamental {
  symbol: string;
  isin: string;
  ratioName: string;
  companyValue: string | null;
  sectorValue: string | null;
  fetchedAt: string;
}

/** One overnight global-market read (US indices, crude oil, USD/INR), fetched once daily
 * shortly before Indian market open. Purely observational — not wired into any prediction yet. */
export interface GlobalMarketSnapshot {
  symbol: string;
  tradingDate: string;
  fetchedAt: string;
  price: number;
  changePct: number | null;
  previousClose: number | null;
}

/** One quarterly-results date for a basket stock. daysUntil is relative to today (IST): 0 = today, -1 = yesterday. */
export interface ResultsCalendarEntry {
  instrument: string;
  resultsDate: string;
  epsEstimate: number | null;
  daysUntil: number;
}

/** End-of-day market screen built by ml-training/daily_screen.py - descriptive statistics, not recommendations. */
export interface ScreenLevel {
  price: number | null;
  distPct: number | null;
}

export interface ScreenStock {
  risk?: ScreenStockRisk;
  symbol: string;
  close: number;
  prevClose: number;
  chg: number;
  gap: number | null;
  openToClose: number | null;
  rsNifty: number;
  volRatio: number | null;
  rsi: number | null;
  atrPct: number | null;
  r5: number | null;
  r20: number | null;
  trend: 'up' | 'down' | 'mixed';
  resultsDate: string | null;
  levels: Record<string, ScreenLevel>;
  band: {
    p5Pct: number | null;
    p50Pct: number | null;
    p95Pct: number | null;
    low: number | null;
    high: number | null;
    medianAbsMovePct: number | null;
    p90AbsMovePct: number | null;
    medianRangePct: number | null;
    upDaysPct: number | null;
    days: number;
  };
  history: {
    similarDays: { n: number; nextDayMeanPct: number | null; nextDayUpPct: number | null; next5dMeanPct: number | null };
    oversold: { n: number; next5dMeanPct: number | null; next5dUpPct: number | null } | null;
  };
}

export interface ScreenIndex {
  close: number;
  chg: number;
  rsi: number | null;
  r5: number | null;
  r20: number | null;
  vsEma21Pct: number | null;
  vsEma200Pct: number | null;
}

export interface ScreenHypothesis {
  id: string;
  title: string;
  claim: string;
  evidence: string;
  passIf: string;
  baseRate: string;
  names?: string[];
  winners?: string[];
  losers?: string[];
  scoreAfterSessions: number;
}

export interface ScreenScoredResult {
  id: string;
  title: string;
  claim: string;
  outcome: 'PASS' | 'FAIL' | 'INCONCLUSIVE' | 'PENDING' | 'N/A';
  detail: string;
}

export interface DailyScreen {
  sessionDate: string;
  nextSession: string;
  asOf: string;
  dataNote: string;
  notAvailable: string[];
  disclaimer: string;
  market: {
    nifty: ScreenIndex;
    banknifty: ScreenIndex;
    breadth: {
      advancers: number;
      decliners: number;
      meanChg: number;
      medianChg: number;
      aboveEma21: number;
      aboveEma200: number;
      trendStack: Record<string, number>;
    };
    global: { symbol: string; price: number; changePct: number; date: string }[];
  };
  lists: Record<'strength' | 'weakness' | 'volume' | 'breakout20d' | 'breakdown20d' | 'rsiExtremes' | 'resultsSoon', string[]>;
  stocks: ScreenStock[];
  hypotheses: ScreenHypothesis[];
  risk?: ScreenRiskSummary | null;
  scoredPrevious: { forSession: string | null; madeOn: string; results: ScreenScoredResult[] } | null;
  tally: Record<string, { title: string; pass: number; fail: number; inconclusive: number }>;
}

/** Next-session risk forecast (ml-training/risk_forecast.py): how BIG the swing is, not which way. Descriptive, not advice. */
export interface ScreenStockRisk {
  expectedRangePct: number;
  upper90RangePct: number;
  bigMoveProbPct: number;
  typicalRangePct: number;
  vsTypicalX: number;
  tier: 'High' | 'Elevated' | 'Normal' | 'Low';
  resultsNext: boolean;
  /** True when the expected range also used the options market's implied volatility (NSE F&O bhavcopy). */
  ivUsed?: boolean;
  impliedVolPct?: number | null;
}

export interface ScreenRiskSummary {
  nextSession: string;
  basketExpectedRangePct: number;
  basketTypicalRangePct: number;
  basketVsTypicalX: number;
  resultsNext: string[];
  tierCounts: Record<string, number>;
  events: { id: string; label: string; effect: string }[];
  note: string;
  validation: {
    testDays: number;
    rangeForecast: Record<'atrlike' | 'har' | 'mean', { mae: number; vsAveragePct: number; spearman: number }>;
    q90Coverage: number;
    bigMoveAuc: number;
    bigMoveBaseRatePct: number;
    bigMoveTopDecilePct: number;
    bigMoveThresholdPct: number;
  } | null;
}

/** Same-day "how far can it still move before the close" forecast (ml-training/swing_model.py) - risk context, not a price call. */
export interface IntradayRiskForecast {
  instrument: string;
  targetDate: string;
  hoursSinceOpen: number;
  predictedAt: string;
  currentPrice: number | null;
  expectedSwingPct: number;
  upper90SwingPct: number;
}

/** MCX commodity report built by ml-training/commodity_report.py (see GET /api/commodities/latest). */
export interface CommodityLevel {
  pct: number;
  price: number;
}

export interface CommodityForecast {
  label: string;
  days: number;
  expiry?: string;
  peak: { p50: CommodityLevel; p75: CommodityLevel; p90: CommodityLevel };
  dip: { p50: CommodityLevel; p75: CommodityLevel; p90: CommodityLevel };
  close: { p10: CommodityLevel; p50: CommodityLevel; p90: CommodityLevel };
  givebackMedianPct: number;
}

export interface CommodityContract {
  symbol: string;
  key: string;
  expiry: string;
  daysToExpiry: number;
  tradingDaysToExpiry: number;
  price: number;
  asOf: string;
  volume: number;
  avgVolume5: number;
  dayChangePct: number | null;
  isReference: boolean;
  spreadVsRefPct: number;
  carryAnnualPct?: number | null;
}

export interface CommodityOptionImplied {
  expiry: string;
  daysToExpiry: number;
  underlying: string | null;
  futuresPrice: number;
  strike: number;
  call: number;
  put: number;
  straddle: number;
  expectedMovePct: number;
  impliedVolAnnualPct: number;
  oneSigmaMovePct: number;
  vsRealizedVol: number;
  note: string;
}

export interface CommodityAfterSimilarMoves {
  bucket: string;
  trailingMovePct: number;
  z: number;
  samples: number;
  pUp: number;
  pUpOthers: number;
  meanCloseZ: number;
  meanCloseZOthers: number;
  t: number;
  peakMedianPct: number;
  peakP90Pct: number;
  dipMedianPct: number;
  dipP90Pct: number;
  distinguishableFromNormal: boolean;
}

export interface CommodityProduct {
  underlying: string;
  family: string;
  familyName: string;
  name: string;
  priceUnit: string;
  lotUnits: number;
  price: number;
  referenceContract: string;
  dayChangePct: number | null;
  asOf: string;
  lotNotional: number;
  contracts: CommodityContract[];
  termStructure: string;
  sigmaDailyPct: number;
  sigmaAnnualPct: number;
  trail21Pct: number;
  trailZ: number;
  trailBucket: string;
  forecasts: CommodityForecast[];
  afterSimilarMoves: CommodityAfterSimilarMoves;
  hasOptions: boolean;
  options: { expiries: { date: string; daysToExpiry: number }[]; implied: CommodityOptionImplied | null } | null;
  stale: boolean;
}

export interface CommodityReport {
  reportDate: string;
  generatedAt: string;
  sessionComplete: boolean;
  products: CommodityProduct[];
  validation: { quantilesBuilt: string; history: Record<string, string>; q90Coverage: Record<string, Record<string, { peak?: number; dip?: number }>> };
  notes: string[];
}

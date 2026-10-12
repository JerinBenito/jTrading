import { useMemo } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useTheme } from '../theme/ThemeContext';
import type { Theme } from '../theme/tokens';
import type { ScreenStock } from '../api/types';
import { Card } from './ui/Card';

const LEVEL_LABELS: Record<string, string> = {
  high52w: '52-week high',
  high20d: '20-day high',
  prevDayHigh: 'Prev-day high',
  ema21: 'EMA 21',
  ema50: 'EMA 50',
  ema200: 'EMA 200',
  prevDayLow: 'Prev-day low',
  low20d: '20-day low',
  low52w: '52-week low',
};

const fmt = (v: number | null | undefined, digits = 2) => (v === null || v === undefined ? '—' : v.toFixed(digits));
const signed = (v: number | null | undefined, digits = 2) =>
  v === null || v === undefined ? '—' : `${v >= 0 ? '+' : ''}${v.toFixed(digits)}%`;

/** Evidence dossier for one stock from the latest end-of-day screen: facts and the stock's own history, never a recommendation. */
export function StockDossierCard({ stock, sessionDate }: { stock: ScreenStock; sessionDate: string }) {
  const { theme: colors } = useTheme();
  const styles = useMemo(() => createStyles(colors), [colors]);
  const sim = stock.history.similarDays;
  const levels = Object.entries(stock.levels)
    .filter(([, v]) => v.price !== null)
    .sort((a, b) => (b[1].price as number) - (a[1].price as number));
  const changeColor = stock.chg >= 0 ? colors.up : colors.down;

  return (
    <Card title="Evidence dossier" subtitle={`End-of-day facts for ${sessionDate} — not a recommendation`}>
      <View style={styles.factGrid}>
        <Fact styles={styles} label="Close" value={fmt(stock.close)} />
        <Fact styles={styles} label="Day" value={signed(stock.chg)} color={changeColor} />
        <Fact styles={styles} label="vs NIFTY" value={signed(stock.rsNifty)} color={stock.rsNifty >= 0 ? colors.up : colors.down} />
        <Fact styles={styles} label="RSI" value={fmt(stock.rsi, 0)} />
        <Fact styles={styles} label="Volume" value={stock.volRatio === null ? '—' : `${stock.volRatio.toFixed(2)}x`} />
        <Fact styles={styles} label="Typical move" value={`${fmt(stock.atrPct)}%`} />
        <Fact styles={styles} label="5-day" value={signed(stock.r5, 1)} />
        <Fact styles={styles} label="20-day" value={signed(stock.r20, 1)} />
      </View>
      {stock.resultsDate && (
        <Text style={[styles.note, { color: colors.neutral }]}>
          Quarterly results: {stock.resultsDate} (vendor date, may shift). Moves are typically about twice as large around results.
        </Text>
      )}

      <Text style={styles.section}>Price levels (distance from close)</Text>
      {levels.map(([key, v]) => (
        <View key={key} style={styles.levelRow}>
          <Text style={styles.levelLabel} numberOfLines={1}>
            {LEVEL_LABELS[key] ?? key}
          </Text>
          <Text style={styles.levelPrice}>{fmt(v.price)}</Text>
          <Text style={[styles.levelDist, { color: (v.distPct ?? 0) >= 0 ? colors.up : colors.down }]}>{signed(v.distPct, 1)}</Text>
        </View>
      ))}

      <Text style={styles.section}>Typical next-day band (this stock's own history, {stock.band.days} days)</Text>
      <Text style={styles.body}>
        90% of next-day moves fell between {signed(stock.band.p5Pct)} and {signed(stock.band.p95Pct)}
        {stock.band.low !== null && stock.band.high !== null ? ` — ${fmt(stock.band.low)} to ${fmt(stock.band.high)}` : ''}. The next day was up on{' '}
        {fmt(stock.band.upDaysPct, 0)}% of days. This describes size, not direction.
      </Text>

      <Text style={styles.section}>After days like today</Text>
      <Text style={styles.body}>
        {sim.n >= 3
          ? `${sim.n} comparable days: next day averaged ${signed(sim.nextDayMeanPct)} and was up ${fmt(sim.nextDayUpPct, 0)}% of the time; the next 5 days averaged ${signed(sim.next5dMeanPct)}.${
              sim.n < 15 ? ' Small sample — treat as an anecdote.' : ''
            }`
          : `Only ${sim.n} comparable days in history — not enough to say anything.`}
      </Text>
      {stock.history.oversold && (
        <Text style={styles.body}>
          Previous times RSI was below 32 ({stock.history.oversold.n} days, clustered into a few episodes): the next 5 days averaged{' '}
          {signed(stock.history.oversold.next5dMeanPct)} and were up {fmt(stock.history.oversold.next5dUpPct, 0)}% of the time.
        </Text>
      )}
    </Card>
  );
}

function Fact({ label, value, color, styles }: { label: string; value: string; color?: string; styles: ReturnType<typeof createStyles> }) {
  return (
    <View style={styles.fact}>
      <Text style={styles.factLabel} numberOfLines={1}>
        {label}
      </Text>
      <Text style={[styles.factValue, color ? { color } : null]} numberOfLines={1} adjustsFontSizeToFit>
        {value}
      </Text>
    </View>
  );
}

function createStyles(colors: Theme) {
  return StyleSheet.create({
    factGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 10 },
    fact: { flexGrow: 1, flexBasis: '30%', minWidth: 0, backgroundColor: colors.surfaceAlt, borderRadius: 12, padding: 10 },
    factLabel: { color: colors.textMuted, fontSize: 10, fontWeight: '600' },
    factValue: { color: colors.textPrimary, fontSize: 14, fontWeight: '700', marginTop: 2 },
    note: { fontSize: 12, lineHeight: 17, marginTop: 10 },
    section: { color: colors.textSecondary, fontSize: 11, fontWeight: '800', letterSpacing: 0.4, marginTop: 16, marginBottom: 6, textTransform: 'uppercase' },
    levelRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 5, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.border },
    levelLabel: { flex: 1, color: colors.textSecondary, fontSize: 12 },
    levelPrice: { width: 82, color: colors.textPrimary, fontSize: 12, fontWeight: '700', textAlign: 'right' },
    levelDist: { width: 62, fontSize: 12, fontWeight: '700', textAlign: 'right' },
    body: { color: colors.textSecondary, fontSize: 12, lineHeight: 18, marginBottom: 6 },
  });
}

import { useCallback, useMemo, useState } from 'react';
import { Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../../src/theme/ThemeContext';
import type { Theme } from '../../src/theme/tokens';
import { useApiData } from '../../src/hooks/useApiData';
import { api } from '../../src/api/client';
import { ScreenHeader } from '../../src/components/ScreenHeader';
import { Card } from '../../src/components/ui/Card';
import { Badge } from '../../src/components/ui/Badge';
import { EmptyState, ErrorState, LoadingState } from '../../src/components/ScreenState';
import type { CommodityContract, CommodityForecast, CommodityProduct, CommodityReport } from '../../src/api/types';

const signed = (v: number | null | undefined, digits = 1) =>
  v === null || v === undefined ? '—' : `${v >= 0 ? '+' : ''}${v.toFixed(digits)}%`;

const rupees = (n: number) => {
  const digits = n >= 1000 ? 0 : n >= 100 ? 1 : 2;
  return `₹${n.toLocaleString('en-IN', { minimumFractionDigits: digits, maximumFractionDigits: digits })}`;
};

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const day = (iso: string) => {
  const [y, m, d] = iso.split('-').map(Number);
  return `${d} ${MONTHS[m - 1]} ${String(y).slice(2)}`;
};

type Styles = ReturnType<typeof createStyles>;

export default function CommoditiesTab() {
  const insets = useSafeAreaInsets();
  const { theme: colors } = useTheme();
  const styles = useMemo(() => createStyles(colors), [colors]);
  const report = useApiData(() => api.getCommodityReport(), []);
  const [manualRefreshing, setManualRefreshing] = useState(false);
  const [family, setFamily] = useState<string>('GOLD');
  const [open, setOpen] = useState<string | null>(null);
  const onRefresh = useCallback(() => {
    setManualRefreshing(true);
    report.refresh();
    setTimeout(() => setManualRefreshing(false), 600);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const data = report.data;
  const families = useMemo(() => {
    const seen = new Map<string, string>();
    (data?.products ?? []).forEach((p) => seen.set(p.family, p.familyName));
    return [...seen.entries()];
  }, [data]);
  const products = (data?.products ?? []).filter((p) => p.family === family);
  const expanded = open ?? products[0]?.underlying ?? null;

  return (
    <ScrollView
      style={styles.screen}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + 12 }]}
      refreshControl={<RefreshControl refreshing={manualRefreshing} onRefresh={onRefresh} tintColor={colors.accent} />}
    >
      <ScreenHeader eyebrow={data ? `MCX · ${data.reportDate}` : 'MCX'} title="Commodities" />
      {report.loading && <LoadingState />}
      {!report.loading && report.error && <ErrorState message={report.error} onRetry={onRefresh} />}
      {!report.loading && !report.error && !data && (
        <EmptyState message="No commodity report yet — it is built after each MCX session (around midnight IST) and at 12:30 IST." />
      )}

      {data && (
        <>
          <View style={styles.disclaimer}>
            <Text style={styles.disclaimerText}>
              Ranges, not predictions. These show how far a price has typically swung in the past at today's volatility. Which
              way it goes is not forecast: after strong one-month moves, the next month was statistically no different from normal.
            </Text>
            <Text style={styles.metaText}>
              Built {data.generatedAt.replace('T', ' ').slice(0, 16)} IST{data.sessionComplete ? '' : ' (session still open — latest price is live)'}.
            </Text>
          </View>

          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chipRow}>
            {families.map(([key, name]) => (
              <Pressable
                key={key}
                onPress={() => {
                  setFamily(key);
                  setOpen(null);
                }}
                style={[styles.chip, family === key && styles.chipOn]}
              >
                <Text style={[styles.chipText, family === key && styles.chipTextOn]}>{name}</Text>
              </Pressable>
            ))}
          </ScrollView>

          {products.map((p) => (
            <ProductCard
              key={p.underlying}
              p={p}
              expanded={expanded === p.underlying}
              onToggle={() => setOpen(expanded === p.underlying ? '' : p.underlying)}
              styles={styles}
              colors={colors}
            />
          ))}

          <Card title="How reliable are these ranges?" subtitle="Tested on data the method had not seen">
            <Text style={styles.body}>
              The bands were built from about 26 years of futures history and re-tested year by year on the last 10 years. The "9 in 10"
              band held {coverageRange(data)} of the time. Gold's upper band is the weakest (it held ~86%) because gold kept trending up.
            </Text>
            {data.notes.map((n, i) => (
              <Text key={i} style={styles.note}>
                • {n}
              </Text>
            ))}
          </Card>
        </>
      )}
    </ScrollView>
  );
}

function coverageRange(data: CommodityReport) {
  const vals: number[] = [];
  Object.values(data.validation.q90Coverage).forEach((byFam) =>
    Object.values(byFam).forEach((c) => {
      if (c.peak !== undefined) vals.push(c.peak);
      if (c.dip !== undefined) vals.push(c.dip);
    }),
  );
  if (vals.length === 0) return '88–90%';
  return `${Math.round(Math.min(...vals))}–${Math.round(Math.max(...vals))}%`;
}

function ProductCard({ p, expanded, onToggle, styles, colors }: { p: CommodityProduct; expanded: boolean; onToggle: () => void; styles: Styles; colors: Theme }) {
  const chg = p.dayChangePct ?? 0;
  return (
    <Card>
      <Pressable onPress={onToggle} style={styles.head}>
        <View style={styles.headLeft}>
          <Text style={styles.name} numberOfLines={1}>
            {p.name}
          </Text>
          <Text style={styles.sub} numberOfLines={1}>
            {p.referenceContract.replace(' FUT ', ' · ')} · {p.priceUnit}
          </Text>
        </View>
        <View style={styles.headRight}>
          <Text style={styles.price}>{rupees(p.price)}</Text>
          <Text style={[styles.chg, { color: chg >= 0 ? colors.up : colors.down }]}>{signed(p.dayChangePct, 2)}</Text>
        </View>
      </Pressable>

      {p.stale && <Badge label="NO RECENT TRADES — PRICE MAY BE STALE" color={colors.neutral} />}

      {expanded && (
        <>
          <Text style={styles.body}>
            Last 21 sessions {signed(p.trail21Pct)} ({p.trailBucket}) · volatility {p.sigmaAnnualPct.toFixed(0)}% a year (about {p.sigmaDailyPct.toFixed(1)}% a day) ·
            one lot is worth about {rupees(p.lotNotional)}.
          </Text>

          <Text style={styles.section}>Contracts and expiry dates</Text>
          <ContractTable contracts={p.contracts} termStructure={p.termStructure} styles={styles} colors={colors} />

          <Forecasts p={p} styles={styles} colors={colors} />

          <Text style={styles.section}>After a move like this one</Text>
          <AfterMoves p={p} styles={styles} />

          <Text style={styles.section}>Options</Text>
          <OptionsBlock p={p} styles={styles} colors={colors} />
        </>
      )}
      {!expanded && <Text style={styles.tapHint}>Tap for expiry dates, ranges and options</Text>}
    </Card>
  );
}

function ContractTable({ contracts, termStructure, styles, colors }: { contracts: CommodityContract[]; termStructure: string; styles: Styles; colors: Theme }) {
  return (
    <View>
      <View style={styles.tHead}>
        <Text style={[styles.th, { flex: 1 }]}>Expiry</Text>
        <Text style={[styles.th, { width: 66, textAlign: 'right' }]}>Price</Text>
        <Text style={[styles.th, { width: 56, textAlign: 'right' }]}>vs main</Text>
        <Text style={[styles.th, { width: 58, textAlign: 'right' }]}>Carry/yr</Text>
      </View>
      {contracts.map((c) => (
        <View key={c.key} style={styles.tRow}>
          <View style={{ flex: 1 }}>
            <Text style={styles.td} numberOfLines={1}>
              {day(c.expiry)}
              {c.isReference ? '  ★' : ''}
            </Text>
            <Text style={styles.tdSub}>
              {c.daysToExpiry} days · {c.tradingDaysToExpiry} sessions
            </Text>
          </View>
          <Text style={[styles.td, { width: 66, textAlign: 'right' }]}>{rupees(c.price)}</Text>
          <Text style={[styles.td, { width: 56, textAlign: 'right', color: colors.textSecondary }]}>{c.isReference ? '—' : signed(c.spreadVsRefPct)}</Text>
          <Text style={[styles.td, { width: 58, textAlign: 'right', color: colors.textSecondary }]}>{c.carryAnnualPct === undefined || c.carryAnnualPct === null ? '—' : signed(c.carryAnnualPct)}</Text>
        </View>
      ))}
      <Text style={styles.tdSub}>★ most-traded contract (used for the ranges below). Term structure: {termStructure}. Carry is what later expiries cost over the nearer one, per year.</Text>
    </View>
  );
}

function Forecasts({ p, styles, colors }: { p: CommodityProduct; styles: Styles; colors: Theme }) {
  const [idx, setIdx] = useState(Math.min(2, p.forecasts.length - 1));
  const f: CommodityForecast = p.forecasts[Math.min(idx, p.forecasts.length - 1)];
  const toExpiry = f.expiry ? ` (also the ${day(f.expiry)} expiry)` : '';
  return (
    <View style={{ gap: 8 }}>
      <Text style={styles.section}>How high or low can it get?</Text>
      <View style={styles.chipRowWrap}>
        {p.forecasts.map((x, i) => (
          <Pressable key={x.label} onPress={() => setIdx(i)} style={[styles.chipSmall, idx === i && styles.chipOn]}>
            <Text style={[styles.chipText, idx === i && styles.chipTextOn]}>{x.label}</Text>
          </Pressable>
        ))}
      </View>
      <Text style={styles.hint}>
        Window: {f.days} trading sessions{toExpiry}. A "high" is the highest price touched in the window, a "low" the lowest.
      </Text>
      <View style={styles.rangeBox}>
        <RangeLine
          label="Highest point"
          color={colors.up}
          typical={f.peak.p50}
          stretch={f.peak.p90}
          note="Half the time the high reaches at least the typical level; 1 time in 10 it reaches the stretch level or more."
          styles={styles}
        />
        <RangeLine
          label="Lowest point"
          color={colors.down}
          typical={f.dip.p50}
          stretch={f.dip.p90}
          note="Half the time the low goes down to at least the typical level; 1 time in 10 it goes down to the stretch level or lower."
          styles={styles}
        />
        <View style={styles.rangeLine}>
          <Text style={styles.rangeLabel}>Where it closes the window (80% of the time)</Text>
          <Text style={styles.rangeValue}>
            {rupees(f.close.p10.price)} to {rupees(f.close.p90.price)}
          </Text>
          <Text style={styles.hint}>
            That is {signed(f.close.p10.pct)} to {signed(f.close.p90.pct)}. Typically the close ends about {Math.abs(f.givebackMedianPct).toFixed(1)}% below the highest point it
            touched — rallies tend to give a part of their peak back.
          </Text>
        </View>
      </View>
    </View>
  );
}

function RangeLine({ label, color, typical, stretch, note, styles }: { label: string; color: string; typical: { pct: number; price: number }; stretch: { pct: number; price: number }; note: string; styles: Styles }) {
  return (
    <View style={styles.rangeLine}>
      <Text style={styles.rangeLabel}>{label}</Text>
      <View style={styles.rangeCols}>
        <View style={styles.rangeCol}>
          <Text style={styles.rangeTiny}>TYPICAL</Text>
          <Text style={[styles.rangeValue, { color }]}>{rupees(typical.price)}</Text>
          <Text style={styles.rangeTiny}>{signed(typical.pct)}</Text>
        </View>
        <View style={styles.rangeCol}>
          <Text style={styles.rangeTiny}>STRETCH (1 IN 10)</Text>
          <Text style={[styles.rangeValue, { color }]}>{rupees(stretch.price)}</Text>
          <Text style={styles.rangeTiny}>{signed(stretch.pct)}</Text>
        </View>
      </View>
      <Text style={styles.hint}>{note}</Text>
    </View>
  );
}

function AfterMoves({ p, styles }: { p: CommodityProduct; styles: Styles }) {
  const a = p.afterSimilarMoves;
  return (
    <View style={{ gap: 6 }}>
      <Text style={styles.body}>
        {p.name} moved {signed(a.trailingMovePct)} over the last 21 sessions, which counts as "{a.bucket}" for this market's volatility. Across {a.samples} comparable stretches in six
        commodities since 2001, the following 21 sessions ended higher {Math.round(a.pUp * 100)}% of the time, against {Math.round(a.pUpOthers * 100)}% for all other months —{' '}
        {a.distinguishableFromNormal ? 'a difference large enough to be statistically real.' : 'no meaningful difference, so the earlier move says nothing about the next month.'}
      </Text>
      <Text style={styles.body}>
        What did differ after such moves was the size of the swings: the high reached at least {signed(a.peakMedianPct)} half of the time ({signed(a.peakP90Pct)} or more 1 time in 10), and the
        low {signed(a.dipMedianPct)} / {signed(a.dipP90Pct)}. So "it is up a lot, how high can it go" has a range answer, but not a direction answer.
      </Text>
    </View>
  );
}

function OptionsBlock({ p, styles, colors }: { p: CommodityProduct; styles: Styles; colors: Theme }) {
  if (!p.hasOptions || !p.options) {
    return <Text style={styles.body}>MCX has no options on this contract — futures only. (Options exist on Gold, Gold Mini, Silver, Silver Mini, Crude Oil, Natural Gas and Copper.)</Text>;
  }
  const o = p.options.implied;
  return (
    <View style={{ gap: 8 }}>
      {o ? (
        <>
          <Text style={styles.body}>
            Options expiring {day(o.expiry)} ({o.daysToExpiry} days) on {o.underlying ?? 'the underlying future'}: the at-the-money call ({rupees(o.call)}) plus put ({rupees(o.put)}) at {rupees(o.strike)} costs{' '}
            {rupees(o.straddle)} — the market is pricing a move of about <Text style={styles.bold}>{o.expectedMovePct.toFixed(1)}%</Text> either way by then (one standard deviation ±
            {o.oneSigmaMovePct.toFixed(1)}%, implied volatility {o.impliedVolAnnualPct.toFixed(0)}% a year).
          </Text>
          <Text style={styles.hint}>
            That is {o.vsRealizedVol.toFixed(1)}× the volatility the contract has actually shown recently. Options are usually priced above realised moves — that premium is what option buyers pay and
            sellers collect, and in SEBI's data most individual F&O traders lost money.
          </Text>
        </>
      ) : (
        <Text style={styles.body}>No liquid at-the-money options to read an implied move from right now.</Text>
      )}
      <Text style={[styles.tdSub, { color: colors.textMuted }]}>Option expiries: {p.options.expiries.map((e) => `${day(e.date)} (${e.daysToExpiry}d)`).join(' · ')}</Text>
    </View>
  );
}

function createStyles(colors: Theme) {
  return StyleSheet.create({
    screen: { flex: 1, backgroundColor: colors.background },
    content: { padding: 16, paddingBottom: 40, gap: 14 },
    disclaimer: { backgroundColor: `${colors.neutral}1F`, borderRadius: 12, borderWidth: 1, borderColor: `${colors.neutral}55`, padding: 12, gap: 6 },
    disclaimerText: { color: colors.textPrimary, fontSize: 12, lineHeight: 17, fontWeight: '600' },
    metaText: { color: colors.textMuted, fontSize: 11, lineHeight: 16 },
    chipRow: { gap: 8, paddingVertical: 2 },
    chipRowWrap: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
    chip: { paddingHorizontal: 14, paddingVertical: 8, borderRadius: 16, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border },
    chipSmall: { paddingHorizontal: 10, paddingVertical: 6, borderRadius: 14, backgroundColor: colors.surfaceAlt, borderWidth: 1, borderColor: colors.border },
    chipOn: { backgroundColor: `${colors.accent}26`, borderColor: colors.accent },
    chipText: { color: colors.textSecondary, fontSize: 12, fontWeight: '700' },
    chipTextOn: { color: colors.accent },
    head: { flexDirection: 'row', alignItems: 'center', gap: 10 },
    headLeft: { flex: 1, flexShrink: 1 },
    headRight: { alignItems: 'flex-end' },
    name: { color: colors.textPrimary, fontSize: 16, fontWeight: '800' },
    sub: { color: colors.textMuted, fontSize: 11, marginTop: 2 },
    price: { color: colors.textPrimary, fontSize: 17, fontWeight: '800' },
    chg: { fontSize: 12, fontWeight: '800', marginTop: 2 },
    tapHint: { color: colors.textMuted, fontSize: 11 },
    body: { color: colors.textSecondary, fontSize: 12, lineHeight: 18 },
    bold: { color: colors.textPrimary, fontWeight: '800' },
    note: { color: colors.textMuted, fontSize: 11, lineHeight: 16 },
    hint: { color: colors.textMuted, fontSize: 11, lineHeight: 16 },
    section: { color: colors.textSecondary, fontSize: 11, fontWeight: '800', letterSpacing: 0.4, marginTop: 8, textTransform: 'uppercase' },
    tHead: { flexDirection: 'row', alignItems: 'center', gap: 6, paddingBottom: 4 },
    th: { color: colors.textMuted, fontSize: 10, fontWeight: '700', textTransform: 'uppercase' },
    tRow: { flexDirection: 'row', alignItems: 'center', gap: 6, paddingVertical: 7, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: colors.border },
    td: { color: colors.textPrimary, fontSize: 12, fontWeight: '700' },
    tdSub: { color: colors.textMuted, fontSize: 10, lineHeight: 15 },
    rangeBox: { gap: 12 },
    rangeLine: { backgroundColor: colors.surfaceAlt, borderRadius: 12, padding: 12, gap: 6 },
    rangeLabel: { color: colors.textPrimary, fontSize: 12, fontWeight: '800' },
    rangeCols: { flexDirection: 'row', gap: 12 },
    rangeCol: { flex: 1 },
    rangeTiny: { color: colors.textMuted, fontSize: 9, fontWeight: '700', letterSpacing: 0.3 },
    rangeValue: { color: colors.textPrimary, fontSize: 15, fontWeight: '800', marginVertical: 1 },
  });
}

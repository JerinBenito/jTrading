import { useCallback, useMemo, useState } from 'react';
import { Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import { useTheme } from '../../src/theme/ThemeContext';
import type { Theme } from '../../src/theme/tokens';
import { useApiData } from '../../src/hooks/useApiData';
import { api } from '../../src/api/client';
import { ScreenHeader } from '../../src/components/ScreenHeader';
import { Card } from '../../src/components/ui/Card';
import { Badge } from '../../src/components/ui/Badge';
import { EmptyState, ErrorState, LoadingState } from '../../src/components/ScreenState';
import type { DailyScreen, ScreenIndex, ScreenScoredResult, ScreenStock } from '../../src/api/types';

const signed = (v: number | null | undefined, digits = 2) =>
  v === null || v === undefined ? '—' : `${v >= 0 ? '+' : ''}${v.toFixed(digits)}%`;

type ListKey = keyof DailyScreen['lists'];

const LISTS: { key: ListKey; title: string; subtitle: string; value: (s: ScreenStock) => string }[] = [
  { key: 'strength', title: 'Relative strength today', subtitle: 'Biggest gain versus NIFTY', value: (s) => signed(s.rsNifty) },
  { key: 'weakness', title: 'Relative weakness today', subtitle: 'Biggest lag versus NIFTY', value: (s) => signed(s.rsNifty) },
  { key: 'volume', title: 'Heaviest volume', subtitle: 'Volume vs typical for the same point in the day (1.0x = normal)', value: (s) => (s.volRatio === null ? '—' : `${s.volRatio.toFixed(2)}x`) },
  { key: 'breakdown20d', title: 'Closed below the 20-day low', subtitle: 'By price only — says nothing about what happens next', value: (s) => signed(s.levels.low20d?.distPct, 1) },
  { key: 'breakout20d', title: 'Closed above the 20-day high', subtitle: 'By price only — says nothing about what happens next', value: (s) => signed(s.levels.high20d?.distPct, 1) },
  { key: 'rsiExtremes', title: 'RSI extremes (below 30 or above 70)', subtitle: 'Oversold / overbought readings', value: (s) => (s.rsi === null ? '—' : s.rsi.toFixed(0)) },
  { key: 'resultsSoon', title: 'Quarterly results coming up', subtitle: 'Vendor dates, may shift', value: (s) => s.resultsDate ?? '—' },
];

export default function ScreenTab() {
  const insets = useSafeAreaInsets();
  const { theme: colors } = useTheme();
  const styles = useMemo(() => createStyles(colors), [colors]);
  const screen = useApiData(() => api.getDailyScreen(), []);
  const [manualRefreshing, setManualRefreshing] = useState(false);
  const onRefresh = useCallback(() => {
    setManualRefreshing(true);
    screen.refresh();
    setTimeout(() => setManualRefreshing(false), 600);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const data = screen.data;
  const bySymbol = useMemo(() => new Map((data?.stocks ?? []).map((s) => [s.symbol, s])), [data]);

  return (
    <ScrollView
      style={styles.screen}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + 12 }]}
      refreshControl={<RefreshControl refreshing={manualRefreshing} onRefresh={onRefresh} tintColor={colors.accent} />}
    >
      <ScreenHeader eyebrow={data ? `END OF DAY · ${data.sessionDate}` : 'END OF DAY'} title="Daily screen" />
      {screen.loading && <LoadingState />}
      {!screen.loading && screen.error && <ErrorState message={screen.error} onRetry={onRefresh} />}
      {!screen.loading && !screen.error && !data && (
        <EmptyState message="No screen yet — it's built every weekday evening after the market closes." />
      )}

      {data && (
        <>
          <View style={styles.disclaimer}>
            <Text style={styles.disclaimerText}>{data.disclaimer}</Text>
            <Text style={styles.metaText}>
              Built {data.asOf.replace('T', ' ')} IST from the final 15:15 candle and completed daily bars. Not available: {data.notAvailable.join(', ')}.
            </Text>
          </View>

          <MarketCard data={data} styles={styles} colors={colors} />

          {LISTS.map((list) => {
            const names = data.lists[list.key] ?? [];
            if (names.length === 0) return null;
            return (
              <Card key={list.key} title={list.title} subtitle={list.subtitle}>
                {names.map((sym) => {
                  const s = bySymbol.get(sym);
                  if (!s) return null;
                  return (
                    <Pressable key={sym} style={styles.row} onPress={() => router.push(`/stock/${sym}`)}>
                      <Text style={styles.rowSymbol} numberOfLines={1}>
                        {sym}
                      </Text>
                      <Text style={styles.rowClose}>{s.close.toFixed(2)}</Text>
                      <Text style={[styles.rowChg, { color: s.chg >= 0 ? colors.up : colors.down }]}>{signed(s.chg)}</Text>
                      <Text style={styles.rowValue} numberOfLines={1}>
                        {list.value(s)}
                      </Text>
                    </Pressable>
                  );
                })}
              </Card>
            );
          })}

          <Card
            title={`Hypotheses for ${data.nextSession}`}
            subtitle="Written down before the session so they can be scored honestly. Testable statements — not trade instructions."
          >
            {data.hypotheses.map((h) => (
              <View key={h.id} style={styles.hyp}>
                <View style={styles.hypHead}>
                  <Badge label={h.id} color={colors.accent} />
                  <Text style={styles.hypTitle}>{h.title}</Text>
                </View>
                <Text style={styles.hypClaim}>{h.claim}</Text>
                <Text style={styles.hypMeta}>Evidence: {h.evidence}</Text>
                <Text style={styles.hypMeta}>
                  Passes if: {h.passIf}. By chance: {h.baseRate}.
                  {h.scoreAfterSessions > 1 ? ` Scored after ${h.scoreAfterSessions} sessions.` : ''}
                </Text>
              </View>
            ))}
          </Card>

          <ScoredCard data={data} styles={styles} colors={colors} />
        </>
      )}
    </ScrollView>
  );
}

function IndexLine({ name, idx, styles, colors }: { name: string; idx: ScreenIndex; styles: ReturnType<typeof createStyles>; colors: Theme }) {
  return (
    <View style={styles.idxBlock}>
      <View style={styles.idxRow}>
        <Text style={styles.idxName}>{name}</Text>
        <Text style={styles.idxClose}>{idx.close.toFixed(1)}</Text>
        <Text style={[styles.idxChg, { color: idx.chg >= 0 ? colors.up : colors.down }]}>{signed(idx.chg)}</Text>
      </View>
      <Text style={styles.idxMeta}>
        RSI {idx.rsi ?? '—'} · 5-day {signed(idx.r5, 1)} · 20-day {signed(idx.r20, 1)} · {signed(idx.vsEma21Pct, 1)} vs 21-EMA · {signed(idx.vsEma200Pct, 1)} vs 200-EMA
      </Text>
    </View>
  );
}

function MarketCard({ data, styles, colors }: { data: DailyScreen; styles: ReturnType<typeof createStyles>; colors: Theme }) {
  const b = data.market.breadth;
  const stack = b.trendStack;
  const globals = data.market.global;
  return (
    <Card title="Market context" subtitle="What the whole basket did, in numbers">
      <IndexLine name="NIFTY" idx={data.market.nifty} styles={styles} colors={colors} />
      <IndexLine name="BANKNIFTY" idx={data.market.banknifty} styles={styles} colors={colors} />
      <Text style={styles.body}>
        Breadth: {b.advancers} up / {b.decliners} down (average {signed(b.meanChg)}). {b.aboveEma21} of 50 above their 21-day EMA, {b.aboveEma200} above the 200-day.
        Trend stacks (EMA 9&gt;21&gt;50): {stack.up ?? 0} up, {stack.mixed ?? 0} mixed, {stack.down ?? 0} down.
      </Text>
      <Text style={styles.body}>
        {globals.map((g) => `${g.symbol.replace('_', ' ')} ${g.price.toLocaleString('en-IN', { maximumFractionDigits: 2 })} (${signed(g.changePct)})`).join(' · ')}
      </Text>
    </Card>
  );
}

function outcomeColor(o: ScreenScoredResult['outcome'], colors: Theme) {
  if (o === 'PASS') return colors.up;
  if (o === 'FAIL') return colors.down;
  return colors.textMuted;
}

function ScoredCard({ data, styles, colors }: { data: DailyScreen; styles: ReturnType<typeof createStyles>; colors: Theme }) {
  const prev = data.scoredPrevious;
  const tally = Object.entries(data.tally ?? {});
  if (!prev && tally.length === 0) {
    return (
      <Card title="Scorecard" subtitle="Each hypothesis is scored against what actually happened">
        <Text style={styles.body}>Nothing scored yet — the first results arrive after the next session closes.</Text>
      </Card>
    );
  }
  return (
    <Card
      title="Scorecard"
      subtitle={prev ? `Hypotheses made ${prev.madeOn}, scored against ${prev.forSession ?? 'the next session'}` : 'Running record'}
    >
      {prev?.results.map((r) => (
        <View key={r.id} style={styles.hyp}>
          <View style={styles.hypHead}>
            <Badge label={r.outcome} color={outcomeColor(r.outcome, colors)} />
            <Text style={styles.hypTitle}>{r.id} · {r.title}</Text>
          </View>
          <Text style={styles.hypMeta}>{r.detail}</Text>
        </View>
      ))}
      {tally.length > 0 && (
        <>
          <Text style={styles.section}>Running tally</Text>
          {tally.map(([id, t]) => {
            const n = t.pass + t.fail;
            return (
              <Text key={id} style={styles.body}>
                {id}: {t.pass} passed, {t.fail} failed{t.inconclusive ? `, ${t.inconclusive} inconclusive` : ''}
                {n > 0 ? ` (${Math.round((100 * t.pass) / n)}%)` : ''} — {t.title}
              </Text>
            );
          })}
          <Text style={styles.hypMeta}>A handful of days proves nothing: expect pass rates near the by-chance rate until dozens of sessions are in.</Text>
        </>
      )}
    </Card>
  );
}

function createStyles(colors: Theme) {
  return StyleSheet.create({
    screen: { flex: 1, backgroundColor: colors.background },
    content: { padding: 16, paddingBottom: 40, gap: 14 },
    disclaimer: { backgroundColor: `${colors.neutral}1F`, borderRadius: 12, borderWidth: 1, borderColor: `${colors.neutral}55`, padding: 12, gap: 6 },
    disclaimerText: { color: colors.textPrimary, fontSize: 12, lineHeight: 17, fontWeight: '600' },
    metaText: { color: colors.textMuted, fontSize: 11, lineHeight: 16 },
    idxBlock: { paddingVertical: 6 },
    idxRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
    idxName: { width: 92, color: colors.textPrimary, fontSize: 13, fontWeight: '800' },
    idxClose: { width: 64, color: colors.textSecondary, fontSize: 12 },
    idxChg: { width: 56, fontSize: 13, fontWeight: '800' },
    idxMeta: { color: colors.textMuted, fontSize: 11, lineHeight: 16, marginTop: 2 },
    body: { color: colors.textSecondary, fontSize: 12, lineHeight: 18, marginTop: 8 },
    row: { flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 9, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.border },
    rowSymbol: { flex: 1, color: colors.textPrimary, fontSize: 13, fontWeight: '700' },
    rowClose: { width: 70, color: colors.textMuted, fontSize: 12, textAlign: 'right' },
    rowChg: { width: 62, fontSize: 12, fontWeight: '700', textAlign: 'right' },
    rowValue: { width: 74, color: colors.textSecondary, fontSize: 12, fontWeight: '600', textAlign: 'right' },
    hyp: { marginTop: 12, gap: 6 },
    hypHead: { flexDirection: 'row', alignItems: 'center', gap: 8 },
    hypTitle: { flex: 1, color: colors.textPrimary, fontSize: 13, fontWeight: '700' },
    hypClaim: { color: colors.textPrimary, fontSize: 12, lineHeight: 18 },
    hypMeta: { color: colors.textMuted, fontSize: 11, lineHeight: 16 },
    section: { color: colors.textSecondary, fontSize: 11, fontWeight: '800', letterSpacing: 0.4, marginTop: 16, textTransform: 'uppercase' },
  });
}

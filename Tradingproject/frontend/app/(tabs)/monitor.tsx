import { useCallback, useMemo, useState } from 'react';
import { FlatList, Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import { colors } from '../../src/constants/colors';
import { useApiData } from '../../src/hooks/useApiData';
import { api } from '../../src/api/client';
import { ScreenHeader } from '../../src/components/ScreenHeader';
import { BasketSnapshotRow } from '../../src/components/BasketSnapshotRow';
import { EmptyState, ErrorState, LoadingState } from '../../src/components/ScreenState';
import type { BasketSnapshot } from '../../src/api/types';

type SortMode = 'symbol' | 'gainers' | 'losers' | 'vsCall';

const SORT_OPTIONS: { key: SortMode; label: string }[] = [
  { key: 'vsCall', label: 'Biggest move vs. call' },
  { key: 'gainers', label: 'Top gainers' },
  { key: 'losers', label: 'Top losers' },
  { key: 'symbol', label: 'A-Z' },
];

function sortSnapshots(snapshots: BasketSnapshot[], mode: SortMode) {
  const copy = [...snapshots];
  if (mode === 'symbol') {
    return copy.sort((a, b) => a.symbol.localeCompare(b.symbol));
  }
  if (mode === 'gainers') {
    return copy.sort((a, b) => b.changePct - a.changePct);
  }
  if (mode === 'losers') {
    return copy.sort((a, b) => a.changePct - b.changePct);
  }
  // vsCall — biggest absolute deviation from today's predicted close first; no-prediction rows sink to the bottom.
  return copy.sort((a, b) => {
    const av = a.deviationFromPredictionPct;
    const bv = b.deviationFromPredictionPct;
    if (av === null && bv === null) return 0;
    if (av === null) return 1;
    if (bv === null) return -1;
    return Math.abs(bv) - Math.abs(av);
  });
}

export default function MonitorScreen() {
  const insets = useSafeAreaInsets();
  const basket = useApiData(() => api.getBasketSnapshot(), []);
  const [sortMode, setSortMode] = useState<SortMode>('vsCall');

  const [manualRefreshing, setManualRefreshing] = useState(false);
  const onRefresh = useCallback(() => {
    setManualRefreshing(true);
    basket.refresh();
    setTimeout(() => setManualRefreshing(false), 600);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const sorted = useMemo(() => sortSnapshots(basket.data ?? [], sortMode), [basket.data, sortMode]);

  return (
    <FlatList
      style={styles.screen}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + 12 }]}
      data={sorted}
      keyExtractor={(item) => item.symbol}
      renderItem={({ item }) => (
        <BasketSnapshotRow snapshot={item} onPress={() => router.push(`/stock/${item.symbol}`)} />
      )}
      ItemSeparatorComponent={() => <View style={styles.separator} />}
      refreshControl={
        <RefreshControl refreshing={manualRefreshing} onRefresh={onRefresh} tintColor={colors.accent} />
      }
      ListHeaderComponent={
        <View style={styles.header}>
          <ScreenHeader
            eyebrow={`${basket.data?.length ?? 0} instruments`}
            title="Market monitor"
          />
          <Text style={styles.subtitle}>
            NIFTY, BANKNIFTY, and the NIFTY 50 basket — live price, trend, RSI, and today's
            close-prediction call. Tap a row for the full intraday chart. No pattern-signal
            confidence is attached here.
          </Text>
          <View style={styles.sortRow}>
            {SORT_OPTIONS.map((option) => {
              const selected = option.key === sortMode;
              return (
                <Pressable
                  key={option.key}
                  onPress={() => setSortMode(option.key)}
                  style={[styles.pill, selected && styles.pillSelected]}
                >
                  <Text style={[styles.pillLabel, selected && styles.pillLabelSelected]}>
                    {option.label}
                  </Text>
                </Pressable>
              );
            })}
          </View>
          {basket.loading && <LoadingState />}
          {!basket.loading && basket.error && <ErrorState message={basket.error} onRetry={onRefresh} />}
        </View>
      }
      ListEmptyComponent={
        !basket.loading && !basket.error ? (
          <EmptyState message="No basket data yet — the basket is backfilled and live-ingested hourly during market hours." />
        ) : null
      }
    />
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.background,
  },
  content: {
    padding: 16,
    paddingBottom: 32,
    gap: 10,
  },
  header: {
    gap: 12,
    marginBottom: 6,
  },
  subtitle: {
    color: colors.textMuted,
    fontSize: 12,
    lineHeight: 17,
  },
  sortRow: {
    flexDirection: 'row',
    backgroundColor: colors.surface,
    borderRadius: 12,
    padding: 4,
    gap: 4,
    borderWidth: 1,
    borderColor: colors.border,
  },
  pill: {
    flex: 1,
    paddingVertical: 8,
    borderRadius: 9,
    alignItems: 'center',
  },
  pillSelected: {
    backgroundColor: colors.accent,
  },
  pillLabel: {
    color: colors.textSecondary,
    fontWeight: '600',
    fontSize: 11,
    textAlign: 'center',
  },
  pillLabelSelected: {
    color: colors.textPrimary,
  },
  separator: {
    height: 8,
  },
});

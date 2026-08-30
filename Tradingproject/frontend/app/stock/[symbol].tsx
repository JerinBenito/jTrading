import { useCallback, useState } from 'react';
import { Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors } from '../../src/constants/colors';
import { useApiData } from '../../src/hooks/useApiData';
import { api } from '../../src/api/client';
import { TrajectoryChart } from '../../src/components/TrajectoryChart';
import { EmptyState, ErrorState, LoadingState } from '../../src/components/ScreenState';

function formatPrice(value: number) {
  return value.toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

export default function StockDetailScreen() {
  const { symbol } = useLocalSearchParams<{ symbol: string }>();
  const insets = useSafeAreaInsets();
  const trajectory = useApiData(() => api.getTrajectory(symbol), [symbol]);

  const [manualRefreshing, setManualRefreshing] = useState(false);
  const onRefresh = useCallback(() => {
    setManualRefreshing(true);
    trajectory.refresh();
    setTimeout(() => setManualRefreshing(false), 600);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const data = trajectory.data;

  return (
    <ScrollView
      style={styles.screen}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + 12 }]}
      refreshControl={
        <RefreshControl refreshing={manualRefreshing} onRefresh={onRefresh} tintColor={colors.accent} />
      }
    >
      <View style={styles.headerRow}>
        <Pressable style={styles.backButton} onPress={() => router.back()} hitSlop={10}>
          <Ionicons name="chevron-back" size={22} color={colors.textPrimary} />
        </Pressable>
        <View>
          <Text style={styles.eyebrow}>INTRADAY CALL</Text>
          <Text style={styles.title}>{symbol}</Text>
        </View>
      </View>

      {trajectory.loading && <LoadingState />}
      {!trajectory.loading && trajectory.error && (
        <ErrorState message={trajectory.error} onRetry={onRefresh} />
      )}

      {!trajectory.loading && !trajectory.error && !data && (
        <EmptyState message={`No close prediction recorded for ${symbol} today yet — it's recorded once the day's first candle arrives.`} />
      )}

      {data && (
        <>
          <View style={styles.summaryRow}>
            <SummaryStat label="Predicted close" value={formatPrice(data.predictedClose)} accent />
            <SummaryStat label="Predicted low" value={formatPrice(data.rangeLow)} />
            <SummaryStat label="Predicted high" value={formatPrice(data.rangeHigh)} />
          </View>

          {data.actualClose !== null && (
            <View style={styles.closedBanner}>
              <Ionicons
                name={data.actualClose >= data.rangeLow && data.actualClose <= data.rangeHigh ? 'checkmark-circle' : 'close-circle'}
                size={16}
                color={data.actualClose >= data.rangeLow && data.actualClose <= data.rangeHigh ? colors.up : colors.down}
              />
              <Text style={styles.closedText}>
                Closed at {formatPrice(data.actualClose)} —{' '}
                {data.actualClose >= data.rangeLow && data.actualClose <= data.rangeHigh
                  ? 'within predicted range'
                  : 'outside predicted range'}
              </Text>
            </View>
          )}

          <TrajectoryChart trajectory={data} />

          <Text style={styles.footnote}>
            Same model already validated on NIFTY/BANKNIFTY: random-walk baseline + gated bias
            correction + self-calibrating range width. Evaluated against the actual close every
            evening, which feeds tomorrow's correction — same discipline, extended to this stock.
          </Text>
        </>
      )}
    </ScrollView>
  );
}

function SummaryStat({ label, value, accent }: { label: string; value: string; accent?: boolean }) {
  return (
    <View style={styles.statCard}>
      <Text style={styles.statLabel}>{label}</Text>
      <Text style={[styles.statValue, accent && { color: colors.accent }]}>{value}</Text>
    </View>
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
    gap: 14,
  },
  headerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    marginBottom: 4,
  },
  backButton: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    alignItems: 'center',
    justifyContent: 'center',
  },
  eyebrow: {
    color: colors.accent,
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 1,
    marginBottom: 2,
  },
  title: {
    color: colors.textPrimary,
    fontSize: 24,
    fontWeight: '800',
    letterSpacing: -0.5,
  },
  summaryRow: {
    flexDirection: 'row',
    gap: 8,
  },
  statCard: {
    flex: 1,
    backgroundColor: colors.surface,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: colors.border,
    padding: 12,
    gap: 4,
  },
  statLabel: {
    color: colors.textMuted,
    fontSize: 10,
    fontWeight: '600',
    textTransform: 'uppercase',
  },
  statValue: {
    color: colors.textPrimary,
    fontSize: 16,
    fontWeight: '800',
  },
  closedBanner: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    backgroundColor: colors.surface,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: colors.border,
    padding: 12,
  },
  closedText: {
    color: colors.textSecondary,
    fontSize: 12,
    flex: 1,
  },
  footnote: {
    color: colors.textMuted,
    fontSize: 11,
    lineHeight: 16,
  },
});

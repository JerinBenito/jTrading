import { useCallback, useState } from 'react';
import { RefreshControl, ScrollView, StyleSheet, Text } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors } from '../../src/constants/colors';
import { useInstrument } from '../../src/context/InstrumentContext';
import { useApiData } from '../../src/hooks/useApiData';
import { useLiveFeed } from '../../src/hooks/useLiveFeed';
import { api } from '../../src/api/client';
import { ScreenHeader } from '../../src/components/ScreenHeader';
import { InstrumentToggle } from '../../src/components/InstrumentToggle';
import { HealthBadge } from '../../src/components/HealthBadge';
import { PredictionCard } from '../../src/components/PredictionCard';
import { TrajectoryChart } from '../../src/components/TrajectoryChart';
import { RangeCalibrationRow } from '../../src/components/RangeCalibrationRow';
import { PredictionComparisonTable } from '../../src/components/PredictionComparisonTable';
import { EmptyState, ErrorState, LoadingState } from '../../src/components/ScreenState';

export default function DashboardScreen() {
  const { instrument } = useInstrument();
  const insets = useSafeAreaInsets();

  const daily = useApiData(() => api.getForecastHistory(instrument, '1d'), [instrument]);
  const hourly = useApiData(() => api.getForecastHistory(instrument, '1h'), [instrument]);
  const trajectory = useApiData(() => api.getTrajectory(instrument), [instrument]);
  const rangeHourly = useApiData(() => api.getRangeCalibration(instrument, '1h'), [instrument]);
  const rangeDaily = useApiData(() => api.getRangeCalibration(instrument, '1d'), [instrument]);
  const comparison = useApiData(() => api.getPredictionComparison(instrument), [instrument]);
  const leaderboard = useApiData(() => api.getModelLeaderboard(instrument), [instrument]);
  const live = useLiveFeed(instrument);

  const [manualRefreshing, setManualRefreshing] = useState(false);
  const onRefresh = useCallback(() => {
    setManualRefreshing(true);
    daily.refresh();
    hourly.refresh();
    trajectory.refresh();
    rangeHourly.refresh();
    rangeDaily.refresh();
    comparison.refresh();
    leaderboard.refresh();
    setTimeout(() => setManualRefreshing(false), 600);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [instrument]);

  const latestDaily = daily.data?.[0] ?? null;
  const latestHourly = hourly.data?.[0] ?? null;
  const loading = daily.loading && hourly.loading;
  const firstError = daily.error ?? hourly.error;
  const aiIntradayRow = comparison.data?.rows.find((r) => r.source === 'AI' && r.label === 'AI same-day close');

  return (
    <ScrollView
      style={styles.screen}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + 12 }]}
      refreshControl={
        <RefreshControl refreshing={manualRefreshing} onRefresh={onRefresh} tintColor={colors.accent} />
      }
    >
      <ScreenHeader eyebrow={instrument} title="Dashboard" right={<HealthBadge />} />

      <InstrumentToggle />

      {loading && <LoadingState />}
      {!loading && firstError && <ErrorState message={firstError} onRetry={onRefresh} />}

      {!loading && !firstError && (
        <>
          {latestDaily ? (
            <PredictionCard
              title="Today's close prediction"
              icon="sunny-outline"
              prediction={latestDaily}
            />
          ) : (
            <EmptyState message="No same-day prediction recorded yet today." />
          )}

          {trajectory.data && (
            <>
              <TrajectoryChart
                trajectory={trajectory.data}
                aiPredictedClose={aiIntradayRow?.predictedPrice}
                liveLtp={live.ltp}
                liveConnected={live.connected}
              />
              {live.error && <Text style={styles.liveErrorText}>Live feed: {live.error}</Text>}
            </>
          )}

          {latestHourly ? (
            <PredictionCard
              title="Latest hourly forecast"
              icon="time-outline"
              prediction={latestHourly}
            />
          ) : (
            <EmptyState message="No hourly forecast recorded yet." />
          )}

          <RangeCalibrationRow hourly={rangeHourly.data} daily={rangeDaily.data} />

          <PredictionComparisonTable rows={comparison.data?.rows ?? []} leaderboard={leaderboard.data} />
        </>
      )}
    </ScrollView>
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
  liveErrorText: {
    color: colors.textMuted,
    fontSize: 11,
    marginTop: -6,
  },
});

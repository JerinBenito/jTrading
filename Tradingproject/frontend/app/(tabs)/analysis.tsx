import { useCallback, useState } from 'react';
import { RefreshControl, ScrollView, StyleSheet, Text } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors } from '../../src/constants/colors';
import { useInstrument } from '../../src/context/InstrumentContext';
import { useApiData } from '../../src/hooks/useApiData';
import { api } from '../../src/api/client';
import { ScreenHeader } from '../../src/components/ScreenHeader';
import { InstrumentToggle } from '../../src/components/InstrumentToggle';
import { BacktestResultTable } from '../../src/components/BacktestResultTable';
import { IntradayReanchorTable } from '../../src/components/IntradayReanchorTable';
import { ErrorState, LoadingState } from '../../src/components/ScreenState';

export default function AnalysisScreen() {
  const { instrument } = useInstrument();
  const insets = useSafeAreaInsets();

  const hourlyModel = useApiData(() => api.getModelBacktest(instrument), [instrument]);
  const hourlyBias = useApiData(() => api.getBiasCorrectionBacktest(instrument), [instrument]);
  const hourlyRange = useApiData(() => api.getRangeCalibrationBacktest(instrument), [instrument]);
  const dailyModel = useApiData(() => api.getDailyModelBacktest(instrument), [instrument]);
  const dailyBias = useApiData(() => api.getDailyBiasCorrectionBacktest(instrument), [instrument]);
  const dailyRange = useApiData(() => api.getDailyRangeCalibrationBacktest(instrument), [instrument]);
  const reanchor = useApiData(() => api.getIntradayReanchorBacktest(instrument), [instrument]);

  const all = [hourlyModel, hourlyBias, hourlyRange, dailyModel, dailyBias, dailyRange, reanchor];
  const loading = all.every((q) => q.loading);
  const firstError = all.find((q) => q.error)?.error ?? null;

  const [manualRefreshing, setManualRefreshing] = useState(false);
  const onRefresh = useCallback(() => {
    setManualRefreshing(true);
    all.forEach((q) => q.refresh());
    setTimeout(() => setManualRefreshing(false), 600);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [instrument]);

  return (
    <ScrollView
      style={styles.screen}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + 12 }]}
      refreshControl={
        <RefreshControl refreshing={manualRefreshing} onRefresh={onRefresh} tintColor={colors.accent} />
      }
    >
      <ScreenHeader eyebrow={instrument} title="Analysis" />
      <Text style={styles.intro}>
        Walk-forward comparisons, recomputed fresh from all stored history every time — this is
        the trial-and-error evidence behind what's actually running live.
      </Text>

      <InstrumentToggle />

      {loading && <LoadingState />}
      {!loading && firstError && <ErrorState message={firstError} onRetry={onRefresh} />}

      {!loading && !firstError && (
        <>
          <Text style={styles.groupLabel}>Same-day close, intraday</Text>
          {reanchor.data && <IntradayReanchorTable result={reanchor.data} />}

          <Text style={styles.groupLabel}>Hourly forecast</Text>
          <BacktestResultTable
            title="Model choice"
            subtitle="random walk vs. EMA-spread momentum"
            results={hourlyModel.data ?? []}
          />
          <BacktestResultTable
            title="Bias correction"
            subtitle="none vs. naive 20-avg vs. significance-gated"
            results={hourlyBias.data ?? []}
          />
          <BacktestResultTable
            title="Range calibration"
            subtitle="fixed ±1x ATR vs. online-adaptive multiplier"
            results={hourlyRange.data ?? []}
          />

          <Text style={styles.groupLabel}>Same-day close</Text>
          <BacktestResultTable
            title="Model choice"
            subtitle="random walk vs. EMA-spread momentum"
            results={dailyModel.data ?? []}
          />
          <BacktestResultTable
            title="Bias correction"
            subtitle="none vs. naive 20-avg vs. significance-gated"
            results={dailyBias.data ?? []}
          />
          <BacktestResultTable
            title="Range calibration"
            subtitle="fixed ±1x ATR vs. online-adaptive multiplier"
            results={dailyRange.data ?? []}
          />
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
  intro: {
    color: colors.textMuted,
    fontSize: 12,
    lineHeight: 17,
    marginTop: -6,
  },
  groupLabel: {
    color: colors.textSecondary,
    fontSize: 13,
    fontWeight: '700',
    marginTop: 6,
  },
});

import { useCallback, useState } from 'react';
import { Pressable, RefreshControl, SectionList, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors } from '../../src/constants/colors';
import { useInstrument } from '../../src/context/InstrumentContext';
import { useApiData } from '../../src/hooks/useApiData';
import { api } from '../../src/api/client';
import { ScreenHeader } from '../../src/components/ScreenHeader';
import { InstrumentToggle } from '../../src/components/InstrumentToggle';
import { PredictionListRow } from '../../src/components/PredictionListRow';
import { EmptyState, ErrorState, LoadingState } from '../../src/components/ScreenState';
import { groupByDay } from '../../src/utils/groupByDay';

type Interval = '1h' | '1d';

export default function HistoryScreen() {
  const { instrument } = useInstrument();
  const insets = useSafeAreaInsets();
  const [interval, setInterval] = useState<Interval>('1h');

  const history = useApiData(() => api.getForecastHistory(instrument, interval), [
    instrument,
    interval,
  ]);

  const [manualRefreshing, setManualRefreshing] = useState(false);
  const onRefresh = useCallback(() => {
    setManualRefreshing(true);
    history.refresh();
    setTimeout(() => setManualRefreshing(false), 600);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [instrument, interval]);

  const sections = groupByDay(history.data ?? [], (p) => p.predictedForTs);

  return (
    <SectionList
      style={styles.screen}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + 12 }]}
      sections={sections}
      keyExtractor={(item) => String(item.id)}
      renderItem={({ item }) => <PredictionListRow prediction={item} />}
      renderSectionHeader={({ section }) => (
        <Text style={styles.sectionHeader}>{section.title}</Text>
      )}
      ItemSeparatorComponent={() => <View style={styles.separator} />}
      stickySectionHeadersEnabled={false}
      refreshControl={
        <RefreshControl refreshing={manualRefreshing} onRefresh={onRefresh} tintColor={colors.accent} />
      }
      ListHeaderComponent={
        <View style={styles.header}>
          <ScreenHeader eyebrow={instrument} title="Prediction history" />
          <InstrumentToggle />
          <View style={styles.intervalToggle}>
            {(['1h', '1d'] as Interval[]).map((option) => {
              const selected = option === interval;
              return (
                <Pressable
                  key={option}
                  onPress={() => setInterval(option)}
                  style={[styles.intervalPill, selected && styles.intervalPillSelected]}
                >
                  <Text style={[styles.intervalLabel, selected && styles.intervalLabelSelected]}>
                    {option === '1h' ? 'Hourly' : 'Same-day close'}
                  </Text>
                </Pressable>
              );
            })}
          </View>
          {history.loading && <LoadingState />}
          {!history.loading && history.error && (
            <ErrorState message={history.error} onRetry={onRefresh} />
          )}
        </View>
      }
      ListEmptyComponent={
        !history.loading && !history.error ? (
          <EmptyState message="No predictions recorded yet for this selection." />
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
    gap: 8,
  },
  header: {
    gap: 14,
    marginBottom: 6,
  },
  intervalToggle: {
    flexDirection: 'row',
    backgroundColor: colors.surface,
    borderRadius: 10,
    padding: 3,
    gap: 3,
    borderWidth: 1,
    borderColor: colors.border,
  },
  intervalPill: {
    flex: 1,
    paddingVertical: 8,
    borderRadius: 7,
    alignItems: 'center',
  },
  intervalPillSelected: {
    backgroundColor: colors.accent,
  },
  intervalLabel: {
    color: colors.textSecondary,
    fontSize: 13,
    fontWeight: '600',
  },
  intervalLabelSelected: {
    color: colors.textPrimary,
  },
  sectionHeader: {
    color: colors.textMuted,
    fontSize: 12,
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: 0.5,
    marginTop: 12,
    marginBottom: 6,
  },
  separator: {
    height: 6,
  },
});

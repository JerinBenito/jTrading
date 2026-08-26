import { useCallback, useState } from 'react';
import { FlatList, RefreshControl, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors } from '../../src/constants/colors';
import { useInstrument } from '../../src/context/InstrumentContext';
import { useApiData } from '../../src/hooks/useApiData';
import { api } from '../../src/api/client';
import { ScreenHeader } from '../../src/components/ScreenHeader';
import { InstrumentToggle } from '../../src/components/InstrumentToggle';
import { SignalListItem } from '../../src/components/SignalListItem';
import { EmptyState, ErrorState, LoadingState } from '../../src/components/ScreenState';

export default function SignalsScreen() {
  const { instrument } = useInstrument();
  const insets = useSafeAreaInsets();
  const signals = useApiData(() => api.getSignalHistory(instrument), [instrument]);

  const [manualRefreshing, setManualRefreshing] = useState(false);
  const onRefresh = useCallback(() => {
    setManualRefreshing(true);
    signals.refresh();
    setTimeout(() => setManualRefreshing(false), 600);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [instrument]);

  return (
    <FlatList
      style={styles.screen}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + 12 }]}
      data={signals.data ?? []}
      keyExtractor={(item) => String(item.id)}
      renderItem={({ item }) => <SignalListItem signal={item} />}
      ItemSeparatorComponent={() => <View style={styles.separator} />}
      refreshControl={
        <RefreshControl refreshing={manualRefreshing} onRefresh={onRefresh} tintColor={colors.accent} />
      }
      ListHeaderComponent={
        <View style={styles.header}>
          <ScreenHeader eyebrow={instrument} title="Pattern signals" />
          <InstrumentToggle />
          {signals.loading && <LoadingState />}
          {!signals.loading && signals.error && (
            <ErrorState message={signals.error} onRetry={onRefresh} />
          )}
        </View>
      }
      ListEmptyComponent={
        !signals.loading && !signals.error ? (
          <EmptyState message="No patterns have fired for this instrument yet — that's normal, they don't fire daily." />
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
    gap: 14,
    marginBottom: 14,
  },
  separator: {
    height: 10,
  },
});

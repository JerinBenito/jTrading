import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../constants/colors';
import { SignalListItem } from './SignalListItem';
import type { SignalHistoryEntry } from '../api/types';

/** Recent pattern-based signals for one instrument — same list item the dedicated Signals tab
 * uses (NIFTY/BANKNIFTY only, via the instrument toggle), reused here so a basket stock's own
 * signal history is visible on its detail screen too, since the tab itself can't reach it. */
export function PatternSignalsSection({ signals }: { signals: SignalHistoryEntry[] }) {
  return (
    <View style={styles.section}>
      <Text style={styles.title}>Pattern signals</Text>
      <Text style={styles.subtitle}>
        Only fires when a known chart pattern actually appears — most days there's nothing here, and that's normal.
      </Text>
      {signals.length === 0 ? (
        <View style={styles.empty}>
          <Text style={styles.emptyText}>No patterns have fired for this stock yet.</Text>
        </View>
      ) : (
        <View style={styles.list}>
          {signals.slice(0, 10).map((signal) => (
            <SignalListItem key={signal.id} signal={signal} />
          ))}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  section: {
    gap: 10,
  },
  title: {
    color: colors.textSecondary,
    fontSize: 13,
    fontWeight: '700',
  },
  subtitle: {
    color: colors.textMuted,
    fontSize: 11,
    lineHeight: 15,
    marginTop: -6,
  },
  list: {
    gap: 8,
  },
  empty: {
    backgroundColor: colors.surface,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: colors.border,
    padding: 16,
  },
  emptyText: {
    color: colors.textMuted,
    fontSize: 12,
  },
});

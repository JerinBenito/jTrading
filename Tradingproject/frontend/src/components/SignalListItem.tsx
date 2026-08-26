import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../constants/colors';
import type { SignalHistoryEntry } from '../api/types';

function formatTime(iso: string) {
  return new Date(iso).toLocaleString('en-IN', {
    timeZone: 'Asia/Kolkata',
    hour: '2-digit',
    minute: '2-digit',
    day: '2-digit',
    month: 'short',
  });
}

function patternLabel(patternId: string) {
  return patternId.replace(/_/g, ' ').toLowerCase();
}

export function SignalListItem({ signal }: { signal: SignalHistoryEntry }) {
  const resolved = signal.actualDirection !== null;
  const correct = resolved && signal.actualDirection === signal.predictedDirection;
  const directionColor = signal.predictedDirection === 'up' ? colors.up : colors.down;

  return (
    <View style={styles.row}>
      <View style={[styles.directionDot, { backgroundColor: directionColor }]} />
      <View style={styles.body}>
        <Text style={styles.pattern}>{patternLabel(signal.patternId)}</Text>
        <Text style={styles.meta}>
          {signal.predictedDirection.toUpperCase()} · {signal.confidenceTier} confidence ·{' '}
          {signal.sampleSize} sample · {formatTime(signal.ts)}
        </Text>
      </View>
      <View style={styles.outcome}>
        {resolved ? (
          <>
            <Text style={[styles.outcomeText, { color: correct ? colors.up : colors.down }]}>
              {correct ? 'Correct' : 'Missed'}
            </Text>
            <Text style={styles.moveText}>
              {signal.actualMovePct! >= 0 ? '+' : ''}
              {signal.actualMovePct!.toFixed(2)}%
            </Text>
          </>
        ) : (
          <Text style={styles.pendingText}>Pending</Text>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.surface,
    borderRadius: 14,
    padding: 14,
    gap: 12,
    borderWidth: 1,
    borderColor: colors.border,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.14,
    shadowRadius: 6,
    elevation: 2,
  },
  directionDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
  },
  body: {
    flex: 1,
    gap: 2,
  },
  pattern: {
    color: colors.textPrimary,
    fontSize: 14,
    fontWeight: '600',
    textTransform: 'capitalize',
  },
  meta: {
    color: colors.textMuted,
    fontSize: 11,
  },
  outcome: {
    alignItems: 'flex-end',
  },
  outcomeText: {
    fontSize: 13,
    fontWeight: '700',
  },
  moveText: {
    color: colors.textSecondary,
    fontSize: 11,
  },
  pendingText: {
    color: colors.neutral,
    fontSize: 12,
    fontWeight: '600',
  },
});

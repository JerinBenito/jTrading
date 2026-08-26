import { Ionicons } from '@expo/vector-icons';
import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../constants/colors';
import type { IntradayReanchorBacktestResult } from '../api/types';

function pct(value: number | null, suffix = '%') {
  return value !== null ? `${value.toFixed(2)}${suffix}` : '—';
}

export function IntradayReanchorTable({ result }: { result: IntradayReanchorBacktestResult }) {
  return (
    <View style={styles.card}>
      <View style={styles.titleRow}>
        <Ionicons name="refresh-outline" size={14} color={colors.textSecondary} />
        <Text style={styles.title}>Intraday re-anchoring</Text>
      </View>
      <Text style={styles.subtitle}>
        static once-a-day prediction vs. re-anchoring to the latest known price, by hour
      </Text>

      <View style={styles.table}>
        <View style={styles.tableHeaderRow}>
          <Text style={[styles.headerCell, styles.hourCell]}>Hour</Text>
          <Text style={[styles.headerCell, styles.valueCell]}>Static err</Text>
          <Text style={[styles.headerCell, styles.valueCell]}>Live err</Text>
          <Text style={[styles.headerCell, styles.valueCell]}>Live cover</Text>
        </View>
        {result.byHour.map((row) => (
          <View key={row.hoursSinceOpen} style={styles.tableRow}>
            <Text style={[styles.cell, styles.hourCell]}>
              {row.hoursSinceOpen === 0 ? 'Open' : `+${row.hoursSinceOpen}h`}
            </Text>
            <Text style={[styles.cell, styles.valueCell, styles.staticCell]}>
              {pct(row.staticMeanAbsErrorPct)}
            </Text>
            <Text style={[styles.cell, styles.valueCell, styles.liveCell]}>
              {pct(row.reanchoredMeanAbsErrorPct)}
            </Text>
            <Text style={[styles.cell, styles.valueCell, styles.liveCell]}>
              {pct(row.reanchoredCoveragePct)}
            </Text>
          </View>
        ))}
      </View>

      <View style={styles.calloutBox}>
        <View style={styles.calloutHeaderRow}>
          <Ionicons name="warning-outline" size={13} color={colors.neutral} />
          <Text style={styles.calloutTitle}>
            When the ~10:15 price already breaks the expected range
          </Text>
        </View>
        <View style={styles.calloutMetrics}>
          <View style={styles.calloutMetric}>
            <Text style={styles.calloutLabel}>Static coverage</Text>
            <Text style={[styles.calloutValue, { color: colors.down }]}>
              {pct(result.bigMorningMiss.staticCoveragePct)}
            </Text>
          </View>
          <Ionicons name="arrow-forward" size={14} color={colors.textMuted} />
          <View style={styles.calloutMetric}>
            <Text style={styles.calloutLabel}>Re-anchored coverage</Text>
            <Text style={[styles.calloutValue, { color: colors.up }]}>
              {pct(result.bigMorningMiss.reanchoredCoveragePct)}
            </Text>
          </View>
        </View>
        <Text style={styles.calloutSample}>
          based on {result.bigMorningMiss.sampleSize} such days in history
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.surface,
    borderRadius: 18,
    padding: 16,
    gap: 4,
    borderWidth: 1,
    borderColor: colors.border,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.18,
    shadowRadius: 10,
    elevation: 3,
  },
  titleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  title: {
    color: colors.textSecondary,
    fontSize: 12,
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: 0.6,
  },
  subtitle: {
    color: colors.textMuted,
    fontSize: 11,
    marginBottom: 10,
  },
  table: {
    gap: 2,
  },
  tableHeaderRow: {
    flexDirection: 'row',
    paddingBottom: 6,
    marginBottom: 4,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
  },
  tableRow: {
    flexDirection: 'row',
    paddingVertical: 5,
  },
  headerCell: {
    color: colors.textMuted,
    fontSize: 10,
    fontWeight: '700',
    textTransform: 'uppercase',
  },
  cell: {
    color: colors.textPrimary,
    fontSize: 12,
    fontWeight: '600',
  },
  hourCell: {
    width: 52,
  },
  valueCell: {
    flex: 1,
    textAlign: 'right',
  },
  staticCell: {
    color: colors.textSecondary,
    fontWeight: '500',
  },
  liveCell: {
    color: colors.accent,
    fontWeight: '700',
  },
  calloutBox: {
    marginTop: 12,
    backgroundColor: 'rgba(242, 184, 75, 0.1)',
    borderRadius: 12,
    borderWidth: 1,
    borderColor: 'rgba(242, 184, 75, 0.3)',
    padding: 12,
    gap: 8,
  },
  calloutHeaderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  calloutTitle: {
    color: colors.textPrimary,
    fontSize: 11,
    fontWeight: '700',
    flex: 1,
  },
  calloutMetrics: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  calloutMetric: {
    alignItems: 'center',
    gap: 1,
  },
  calloutLabel: {
    color: colors.textMuted,
    fontSize: 10,
  },
  calloutValue: {
    fontSize: 18,
    fontWeight: '800',
  },
  calloutSample: {
    color: colors.textMuted,
    fontSize: 10,
    textAlign: 'center',
  },
});

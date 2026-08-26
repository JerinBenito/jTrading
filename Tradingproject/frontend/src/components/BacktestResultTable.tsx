import { Ionicons } from '@expo/vector-icons';
import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../constants/colors';
import type { BacktestResult, RangeCalibrationBacktestResult } from '../api/types';

function prettyName(modelName: string) {
  return modelName
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

export function BacktestResultTable({
  title,
  subtitle,
  results,
}: {
  title: string;
  subtitle: string;
  results: (BacktestResult | RangeCalibrationBacktestResult)[];
}) {
  const validErrors = results
    .map((r) => r.meanAbsoluteErrorPct)
    .filter((v): v is number => v !== null);
  const bestError = validErrors.length > 0 ? Math.min(...validErrors) : null;

  return (
    <View style={styles.card}>
      <View style={styles.titleRow}>
        <Ionicons name="git-compare-outline" size={14} color={colors.textSecondary} />
        <Text style={styles.title}>{title}</Text>
      </View>
      <Text style={styles.subtitle}>{subtitle}</Text>

      {results.length === 0 ? (
        <Text style={styles.empty}>Not enough history yet to compute this.</Text>
      ) : (
        <View style={styles.table}>
          {results.map((result) => {
            const isBest = bestError !== null && result.meanAbsoluteErrorPct === bestError;
            const multiplier = (result as RangeCalibrationBacktestResult).finalMultiplier;
            return (
              <View key={result.modelName} style={[styles.rowItem, isBest && styles.rowItemBest]}>
                <View style={styles.rowHeader}>
                  <Text style={styles.rowName}>{prettyName(result.modelName)}</Text>
                  {isBest && (
                    <View style={styles.bestBadge}>
                      <Ionicons name="trophy-outline" size={10} color={colors.up} />
                      <Text style={styles.bestBadgeText}>Best</Text>
                    </View>
                  )}
                </View>
                <View style={styles.metricsRow}>
                  <Metric
                    label="Avg error"
                    value={result.meanAbsoluteErrorPct !== null ? `${result.meanAbsoluteErrorPct.toFixed(4)}%` : '—'}
                  />
                  <Metric
                    label="In range"
                    value={
                      result.pctActualWithinPredictedRange !== null
                        ? `${result.pctActualWithinPredictedRange.toFixed(2)}%`
                        : '—'
                    }
                  />
                  {multiplier !== undefined && (
                    <Metric label="Multiplier" value={multiplier !== null ? `${multiplier.toFixed(3)}x` : '—'} />
                  )}
                </View>
                <Text style={styles.sampleSize}>{result.sampleSize.toLocaleString('en-IN')} samples</Text>
              </View>
            );
          })}
        </View>
      )}
    </View>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.metric}>
      <Text style={styles.metricValue}>{value}</Text>
      <Text style={styles.metricLabel}>{label}</Text>
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
    marginBottom: 8,
  },
  empty: {
    color: colors.textMuted,
    fontSize: 12,
    paddingVertical: 4,
  },
  table: {
    gap: 8,
  },
  rowItem: {
    backgroundColor: colors.surfaceAlt,
    borderRadius: 12,
    padding: 12,
    gap: 8,
  },
  rowItemBest: {
    borderWidth: 1,
    borderColor: 'rgba(62, 207, 142, 0.4)',
  },
  rowHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  rowName: {
    color: colors.textPrimary,
    fontSize: 13,
    fontWeight: '700',
  },
  bestBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 3,
    backgroundColor: 'rgba(62, 207, 142, 0.16)',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 6,
  },
  bestBadgeText: {
    color: colors.up,
    fontSize: 10,
    fontWeight: '700',
  },
  metricsRow: {
    flexDirection: 'row',
    gap: 20,
  },
  metric: {
    gap: 1,
  },
  metricValue: {
    color: colors.textPrimary,
    fontSize: 15,
    fontWeight: '700',
  },
  metricLabel: {
    color: colors.textMuted,
    fontSize: 10,
  },
  sampleSize: {
    color: colors.textMuted,
    fontSize: 10,
  },
});

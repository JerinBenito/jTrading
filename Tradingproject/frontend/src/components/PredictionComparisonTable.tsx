import { Ionicons } from '@expo/vector-icons';
import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../constants/colors';
import type { PredictionComparisonRow } from '../api/types';

function formatPrice(value: number | null) {
  return value !== null ? value.toLocaleString('en-IN', { maximumFractionDigits: 2 }) : '—';
}

export function PredictionComparisonTable({ rows }: { rows: PredictionComparisonRow[] }) {
  if (rows.length === 0) {
    return (
      <View style={styles.card}>
        <Text style={styles.title}>Model comparison</Text>
        <Text style={styles.empty}>No predictions recorded for this day yet.</Text>
      </View>
    );
  }

  return (
    <View style={styles.card}>
      <Text style={styles.title}>Model comparison</Text>
      <Text style={styles.subtitle}>Every model's call for this day, side by side with what actually happened.</Text>

      {rows.map((row) => (
        <RowCard key={`${row.source}-${row.label}`} row={row} />
      ))}
    </View>
  );
}

function RowCard({ row }: { row: PredictionComparisonRow }) {
  const isAi = row.source === 'AI';
  const accentColor = isAi ? colors.ai : colors.accent;
  const hit = row.evaluated
    ? row.actualPrice !== null &&
      ((row.rangeLow !== null && row.rangeHigh !== null && row.actualPrice >= row.rangeLow && row.actualPrice <= row.rangeHigh) ||
        row.betterThanBaseline === true)
    : null;

  return (
    <View style={styles.row}>
      <View style={styles.rowHeader}>
        <View style={[styles.sourceBadge, { backgroundColor: `${accentColor}22` }]}>
          <Ionicons name={isAi ? 'sparkles-outline' : 'calculator-outline'} size={11} color={accentColor} />
          <Text style={[styles.sourceBadgeText, { color: accentColor }]}>{row.source}</Text>
        </View>
        <Text style={styles.label}>{row.label}</Text>
      </View>

      <View style={styles.valuesRow}>
        <Value label="Predicted" value={formatPrice(row.predictedPrice)} accent={accentColor} />
        {row.rangeLow !== null && row.rangeHigh !== null && (
          <Value label="Range" value={`${formatPrice(row.rangeLow)} – ${formatPrice(row.rangeHigh)}`} />
        )}
        {row.baselinePrice !== null && <Value label="Baseline" value={formatPrice(row.baselinePrice)} />}
        <Value label="Actual" value={formatPrice(row.actualPrice)} />
      </View>

      <View style={styles.footerRow}>
        {!row.evaluated ? (
          <Text style={styles.pendingText}>Pending</Text>
        ) : (
          <>
            {hit !== null && (
              <View style={styles.resultBadge}>
                <Ionicons name={hit ? 'checkmark-circle' : 'close-circle'} size={12} color={hit ? colors.up : colors.down} />
                <Text style={[styles.resultText, { color: hit ? colors.up : colors.down }]}>
                  {hit ? 'On target' : 'Missed'}
                </Text>
              </View>
            )}
            {row.directionCorrect !== null && (
              <Text style={styles.detailText}>
                direction {row.directionCorrect ? 'correct' : 'wrong'}
              </Text>
            )}
            {row.betterThanBaseline !== null && (
              <Text style={styles.detailText}>
                {row.betterThanBaseline ? 'beat' : 'lost to'} baseline
              </Text>
            )}
          </>
        )}
      </View>
    </View>
  );
}

function Value({ label, value, accent }: { label: string; value: string; accent?: string }) {
  return (
    <View style={styles.valueBox}>
      <Text style={styles.valueLabel}>{label}</Text>
      <Text style={[styles.valueText, accent ? { color: accent } : null]}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.surface,
    borderRadius: 18,
    padding: 16,
    gap: 10,
    borderWidth: 1,
    borderColor: colors.border,
  },
  title: {
    color: colors.textPrimary,
    fontSize: 15,
    fontWeight: '700',
  },
  subtitle: {
    color: colors.textMuted,
    fontSize: 11,
    marginTop: -6,
    lineHeight: 15,
  },
  empty: {
    color: colors.textMuted,
    fontSize: 12,
  },
  row: {
    backgroundColor: colors.surfaceAlt,
    borderRadius: 14,
    padding: 12,
    gap: 8,
  },
  rowHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  sourceBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 3,
    paddingHorizontal: 7,
    paddingVertical: 3,
    borderRadius: 7,
  },
  sourceBadgeText: {
    fontSize: 9,
    fontWeight: '800',
    letterSpacing: 0.4,
  },
  label: {
    color: colors.textPrimary,
    fontSize: 13,
    fontWeight: '600',
    flexShrink: 1,
  },
  valuesRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 14,
  },
  valueBox: {
    gap: 1,
    minWidth: 70,
  },
  valueLabel: {
    color: colors.textMuted,
    fontSize: 10,
    fontWeight: '600',
    textTransform: 'uppercase',
  },
  valueText: {
    color: colors.textPrimary,
    fontSize: 13,
    fontWeight: '700',
  },
  footerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    flexWrap: 'wrap',
  },
  pendingText: {
    color: colors.neutral,
    fontSize: 11,
    fontWeight: '600',
  },
  resultBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 3,
  },
  resultText: {
    fontSize: 11,
    fontWeight: '700',
  },
  detailText: {
    color: colors.textMuted,
    fontSize: 10,
  },
});

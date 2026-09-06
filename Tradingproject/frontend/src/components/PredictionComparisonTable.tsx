import { Ionicons } from '@expo/vector-icons';
import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../constants/colors';
import { Badge } from './ui/Badge';
import { Card } from './ui/Card';
import type { ModelLeaderboard, PredictionComparisonRow } from '../api/types';

function formatPrice(value: number | null) {
  return value !== null ? value.toLocaleString('en-IN', { maximumFractionDigits: 2 }) : '—';
}

function formatPct(value: number) {
  return `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`;
}

/** Price + how far it's moved from the prediction, together — never just a bare percentage. */
function PriceWithDelta({ price, predictedPrice, color }: { price: number; predictedPrice: number; color: string }) {
  const pct = ((price - predictedPrice) / predictedPrice) * 100;
  return (
    <Text style={styles.valueText}>
      {formatPrice(price)} <Text style={[styles.deltaInline, { color }]}>({formatPct(pct)})</Text>
    </Text>
  );
}

export function PredictionComparisonTable({
  rows,
  leaderboard,
}: {
  rows: PredictionComparisonRow[];
  leaderboard?: ModelLeaderboard | null;
}) {
  if (rows.length === 0) {
    return (
      <Card title="Model comparison">
        <Text style={styles.empty}>No predictions recorded for this day yet.</Text>
      </Card>
    );
  }

  return (
    <Card title="Model comparison" subtitle="Every model's call for this day, side by side with what actually happened.">
      {leaderboard && leaderboard.sampleSize > 0 && (
        <View style={styles.leaderboard}>
          <Ionicons name="trophy-outline" size={13} color={colors.textSecondary} />
          <Text style={styles.leaderboardText}>
            Last {leaderboard.sampleSize} shared days —{' '}
            <Text style={{ color: colors.accent, fontWeight: '700' }}>deterministic {leaderboard.deterministicWins}</Text>
            {', '}
            <Text style={{ color: colors.ai, fontWeight: '700' }}>AI {leaderboard.aiWins}</Text>
            {leaderboard.ties > 0 ? `, ${leaderboard.ties} tied` : ''}
          </Text>
        </View>
      )}

      {rows.map((row, i) => (
        <RowCard key={`${row.source}-${row.label}-${i}`} row={row} />
      ))}
    </Card>
  );
}

function RowCard({ row }: { row: PredictionComparisonRow }) {
  const isAi = row.source === 'AI';
  const accentColor = isAi ? colors.ai : colors.accent;
  const isExperimental = row.label.includes('unvalidated');
  const referencePrice = row.actualPrice ?? row.currentPrice;
  const hit = row.evaluated
    ? row.actualPrice !== null &&
      ((row.rangeLow !== null && row.rangeHigh !== null && row.actualPrice >= row.rangeLow && row.actualPrice <= row.rangeHigh) ||
        row.betterThanBaseline === true)
    : null;

  return (
    <View style={styles.row}>
      {/* Badges on their own line, label on the next — a label of any length can wrap freely
       * across the full card width without ever squeezing against a badge. */}
      <View style={styles.badgeRow}>
        <Badge
          label={row.source}
          color={accentColor}
          icon={<Ionicons name={isAi ? 'sparkles-outline' : 'calculator-outline'} size={11} color={accentColor} />}
        />
        {isExperimental && <Badge label="NOT VALIDATED" color={colors.neutral} />}
      </View>
      <Text style={styles.label}>{row.label}</Text>

      <View style={styles.valuesRow}>
        <Value label="Predicted" value={formatPrice(row.predictedPrice)} accent={accentColor} />
        {row.rangeLow !== null && row.rangeHigh !== null && (
          <Value label={isAi ? 'Error band' : 'Range'} value={`${formatPrice(row.rangeLow)} – ${formatPrice(row.rangeHigh)}`} />
        )}
        {row.baselinePrice !== null && <Value label="Baseline" value={formatPrice(row.baselinePrice)} />}
        <View style={styles.valueBox}>
          <Text style={styles.valueLabel}>{row.evaluated ? 'Actual' : 'Current'}</Text>
          {referencePrice !== null && row.predictedPrice !== null ? (
            <PriceWithDelta
              price={referencePrice}
              predictedPrice={row.predictedPrice}
              color={referencePrice >= row.predictedPrice ? colors.up : colors.down}
            />
          ) : (
            <Text style={styles.valueText}>—</Text>
          )}
        </View>
      </View>

      <View style={styles.footerRow}>
        {!row.evaluated ? (
          <Text style={styles.pendingText}>
            {row.currentPrice !== null ? 'Live — not yet scored' : 'Pending'}
          </Text>
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
  empty: {
    color: colors.textMuted,
    fontSize: 12,
  },
  leaderboard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: colors.surfaceAlt,
    borderRadius: 10,
    paddingHorizontal: 10,
    paddingVertical: 7,
  },
  leaderboardText: {
    color: colors.textSecondary,
    fontSize: 11,
    flexShrink: 1,
  },
  row: {
    backgroundColor: colors.surfaceAlt,
    borderRadius: 14,
    padding: 12,
    gap: 8,
  },
  badgeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  label: {
    color: colors.textPrimary,
    fontSize: 13,
    fontWeight: '600',
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
  deltaInline: {
    fontSize: 11,
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

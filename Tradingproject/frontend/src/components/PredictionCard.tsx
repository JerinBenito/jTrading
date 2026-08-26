import { Ionicons } from '@expo/vector-icons';
import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../constants/colors';
import type { Prediction } from '../api/types';

function formatTime(iso: string) {
  return new Date(iso).toLocaleString('en-IN', {
    timeZone: 'Asia/Kolkata',
    hour: '2-digit',
    minute: '2-digit',
    day: '2-digit',
    month: 'short',
  });
}

function formatPrice(value: number) {
  return value.toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

export function PredictionCard({
  title,
  icon,
  prediction,
}: {
  title: string;
  icon?: keyof typeof Ionicons.glyphMap;
  prediction: Prediction;
}) {
  const isEvaluated = prediction.actualClose !== null;
  const covered =
    isEvaluated &&
    prediction.actualClose! >= prediction.rangeLow &&
    prediction.actualClose! <= prediction.rangeHigh;
  const accentColor = !isEvaluated ? colors.neutral : covered ? colors.up : colors.down;

  return (
    <View style={styles.card}>
      <View style={[styles.accentBar, { backgroundColor: accentColor }]} />
      <View style={styles.body}>
        <View style={styles.headerRow}>
          <View style={styles.titleRow}>
            {icon && <Ionicons name={icon} size={14} color={colors.textSecondary} />}
            <Text style={styles.title}>{title}</Text>
          </View>
          <Text style={styles.timestamp}>{formatTime(prediction.predictedForTs)}</Text>
        </View>

        <Text style={styles.predictedClose}>{formatPrice(prediction.predictedClose)}</Text>
        <Text style={styles.range}>
          range {formatPrice(prediction.rangeLow)} – {formatPrice(prediction.rangeHigh)}
        </Text>

        <View style={styles.resultRow}>
          <View
            style={[
              styles.badge,
              { backgroundColor: !isEvaluated ? 'rgba(242, 184, 75, 0.16)' : covered ? 'rgba(62, 207, 142, 0.16)' : 'rgba(242, 84, 91, 0.16)' },
            ]}
          >
            <Ionicons
              name={!isEvaluated ? 'time-outline' : covered ? 'checkmark-circle' : 'close-circle'}
              size={12}
              color={accentColor}
            />
            <Text style={[styles.badgeText, { color: accentColor }]}>
              {!isEvaluated ? 'Pending' : covered ? 'In range' : 'Missed range'}
            </Text>
          </View>
          {isEvaluated && (
            <Text style={styles.resultText}>
              actual {formatPrice(prediction.actualClose!)} ({prediction.errorPct! >= 0 ? '+' : ''}
              {prediction.errorPct!.toFixed(2)}%)
            </Text>
          )}
        </View>

        {prediction.biasCorrectionApplied !== null && prediction.biasCorrectionApplied !== 0 && (
          <Text style={styles.footnote}>
            bias correction applied: {prediction.biasCorrectionApplied > 0 ? '+' : ''}
            {prediction.biasCorrectionApplied.toFixed(2)}
          </Text>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    flexDirection: 'row',
    backgroundColor: colors.surface,
    borderRadius: 18,
    overflow: 'hidden',
    borderWidth: 1,
    borderColor: colors.border,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.18,
    shadowRadius: 10,
    elevation: 3,
  },
  accentBar: {
    width: 4,
  },
  body: {
    flex: 1,
    padding: 16,
    gap: 6,
  },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
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
  timestamp: {
    color: colors.textMuted,
    fontSize: 12,
  },
  predictedClose: {
    color: colors.textPrimary,
    fontSize: 34,
    fontWeight: '800',
    letterSpacing: -0.5,
  },
  range: {
    color: colors.textSecondary,
    fontSize: 13,
  },
  resultRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginTop: 8,
    flexWrap: 'wrap',
  },
  badge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 8,
  },
  badgeText: {
    fontSize: 11,
    fontWeight: '700',
  },
  resultText: {
    color: colors.textSecondary,
    fontSize: 13,
  },
  footnote: {
    color: colors.textMuted,
    fontSize: 11,
    marginTop: 4,
  },
});

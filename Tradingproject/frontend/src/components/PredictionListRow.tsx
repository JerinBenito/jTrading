import { useMemo } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useTheme } from '../theme/ThemeContext';
import type { Theme } from '../theme/tokens';
import type { Prediction } from '../api/types';

function formatTime(iso: string) {
  return new Date(iso).toLocaleTimeString('en-IN', {
    timeZone: 'Asia/Kolkata',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function formatPrice(value: number) {
  return value.toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

export function PredictionListRow({ prediction }: { prediction: Prediction }) {
  const { theme: colors } = useTheme();
  const styles = useMemo(() => createStyles(colors), [colors]);
  const isEvaluated = prediction.actualClose !== null;
  const covered =
    isEvaluated &&
    prediction.actualClose! >= prediction.rangeLow &&
    prediction.actualClose! <= prediction.rangeHigh;
  const hasBiasCorrection =
    prediction.biasCorrectionApplied !== null && prediction.biasCorrectionApplied !== 0;

  return (
    <View style={styles.row}>
      <View
        style={[
          styles.statusDot,
          { backgroundColor: !isEvaluated ? colors.neutral : covered ? colors.up : colors.down },
        ]}
      />
      <View style={styles.body}>
        <Text style={styles.time}>{formatTime(prediction.predictedForTs)}</Text>
        <Text style={styles.predicted} numberOfLines={1} ellipsizeMode="tail">
          predicted {formatPrice(prediction.predictedClose)}
          {hasBiasCorrection && (
            <Text style={styles.correction}>
              {' '}
              ({prediction.biasCorrectionApplied! > 0 ? '+' : ''}
              {prediction.biasCorrectionApplied!.toFixed(2)} correction)
            </Text>
          )}
        </Text>
      </View>
      <View style={styles.result}>
        {isEvaluated ? (
          <>
            <Text style={styles.actual}>{formatPrice(prediction.actualClose!)}</Text>
            <Text style={[styles.errorPct, { color: covered ? colors.textSecondary : colors.down }]}>
              {prediction.errorPct! >= 0 ? '+' : ''}
              {prediction.errorPct!.toFixed(2)}%
            </Text>
          </>
        ) : (
          <Text style={styles.pending}>pending</Text>
        )}
      </View>
    </View>
  );
}

function createStyles(colors: Theme) {
  return StyleSheet.create({
    row: {
      flexDirection: 'row',
      alignItems: 'center',
      backgroundColor: colors.surface,
      borderRadius: 12,
      paddingVertical: 10,
      paddingHorizontal: 12,
      gap: 10,
      borderWidth: 1,
      borderColor: colors.border,
    },
    statusDot: {
      width: 8,
      height: 8,
      borderRadius: 4,
    },
    body: {
      flex: 1,
      minWidth: 0,
      gap: 2,
    },
    time: {
      color: colors.textMuted,
      fontSize: 11,
    },
    predicted: {
      color: colors.textPrimary,
      fontSize: 14,
      fontWeight: '600',
    },
    correction: {
      color: colors.textMuted,
      fontSize: 11,
      fontWeight: '400',
    },
    result: {
      alignItems: 'flex-end',
      flexShrink: 0,
    },
    actual: {
      color: colors.textPrimary,
      fontSize: 14,
      fontWeight: '600',
    },
    errorPct: {
      fontSize: 11,
    },
    pending: {
      color: colors.neutral,
      fontSize: 12,
      fontWeight: '600',
    },
  });
}

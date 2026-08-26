import { Ionicons } from '@expo/vector-icons';
import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../constants/colors';
import type { RangeCalibrationStatus } from '../api/types';

export function RangeCalibrationRow({
  hourly,
  daily,
}: {
  hourly: RangeCalibrationStatus | null;
  daily: RangeCalibrationStatus | null;
}) {
  return (
    <View style={styles.card}>
      <View style={styles.titleRow}>
        <Ionicons name="options-outline" size={14} color={colors.textSecondary} />
        <Text style={styles.title}>Range calibration</Text>
      </View>
      <Text style={styles.subtitle}>self-tuning width multiplier, from real hit-rate</Text>
      <View style={styles.row}>
        <Cell label="Hourly" status={hourly} />
        <View style={styles.divider} />
        <Cell label="Daily" status={daily} />
      </View>
    </View>
  );
}

function Cell({ label, status }: { label: string; status: RangeCalibrationStatus | null }) {
  const multiplier = status?.multiplier ?? 1;
  const widened = multiplier > 1.005;
  const narrowed = multiplier < 0.995;
  const color = widened ? colors.down : narrowed ? colors.up : colors.textSecondary;
  const icon = widened ? 'trending-up' : narrowed ? 'trending-down' : 'remove-outline';

  return (
    <View style={styles.cell}>
      <Text style={styles.cellLabel}>{label}</Text>
      <View style={styles.cellValueRow}>
        <Ionicons name={icon as any} size={16} color={color} />
        <Text style={[styles.cellValue, { color }]}>{multiplier.toFixed(3)}x</Text>
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
    marginBottom: 8,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  divider: {
    width: 1,
    height: 36,
    backgroundColor: colors.border,
    marginHorizontal: 16,
  },
  cell: {
    flex: 1,
  },
  cellLabel: {
    color: colors.textMuted,
    fontSize: 12,
  },
  cellValueRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    marginTop: 2,
  },
  cellValue: {
    fontSize: 20,
    fontWeight: '800',
  },
});

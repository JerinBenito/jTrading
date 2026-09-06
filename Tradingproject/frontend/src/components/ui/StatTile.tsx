import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../../constants/colors';

/** One compact metric — a label, a value, and an optional colored delta underneath. Used
 * anywhere a screen needs a small grid of numbers (global markets, key ratios) instead of a
 * full table, so those sections stay scannable rather than dense. */
export function StatTile({
  label,
  value,
  deltaText,
  deltaColor,
}: {
  label: string;
  value: string;
  deltaText?: string;
  deltaColor?: string;
}) {
  return (
    <View style={styles.tile}>
      <Text style={styles.label} numberOfLines={1}>
        {label}
      </Text>
      <Text style={styles.value} numberOfLines={1} adjustsFontSizeToFit>
        {value}
      </Text>
      {deltaText && (
        <Text style={[styles.delta, { color: deltaColor ?? colors.textMuted }]} numberOfLines={1}>
          {deltaText}
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  tile: {
    flexGrow: 1,
    flexBasis: '30%',
    backgroundColor: colors.surfaceAlt,
    borderRadius: 14,
    paddingVertical: 10,
    paddingHorizontal: 12,
    gap: 3,
  },
  label: {
    color: colors.textMuted,
    fontSize: 10,
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: 0.4,
  },
  value: {
    color: colors.textPrimary,
    fontSize: 15,
    fontWeight: '700',
  },
  delta: {
    fontSize: 11,
    fontWeight: '700',
  },
});

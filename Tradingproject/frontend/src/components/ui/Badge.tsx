import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../../constants/colors';

/** Small, consistent label chip used across the app (source tags, status pills, warnings) —
 * one shared shape instead of every screen inventing its own badge styling. */
export function Badge({
  label,
  color = colors.textSecondary,
  icon,
}: {
  label: string;
  color?: string;
  icon?: React.ReactNode;
}) {
  return (
    <View style={[styles.badge, { backgroundColor: `${color}1F` }]}>
      {icon}
      <Text style={[styles.text, { color }]} numberOfLines={1}>
        {label}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  badge: {
    flexDirection: 'row',
    alignItems: 'center',
    alignSelf: 'flex-start',
    gap: 4,
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 8,
  },
  text: {
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 0.4,
  },
});

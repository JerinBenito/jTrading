import { useMemo } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useTheme } from '../../theme/ThemeContext';
import type { Theme } from '../../theme/tokens';

/** Small, consistent label chip used across the app (source tags, status pills, warnings) —
 * one shared shape instead of every screen inventing its own badge styling. */
export function Badge({
  label,
  color,
  icon,
}: {
  label: string;
  color?: string;
  icon?: React.ReactNode;
}) {
  const { theme: colors } = useTheme();
  const styles = useMemo(() => createStyles(colors), [colors]);
  const resolvedColor = color ?? colors.textSecondary;

  return (
    <View style={[styles.badge, { backgroundColor: `${resolvedColor}1F` }]}>
      {icon}
      <Text style={[styles.text, { color: resolvedColor }]} numberOfLines={1}>
        {label}
      </Text>
    </View>
  );
}

function createStyles(colors: Theme) {
  return StyleSheet.create({
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
}

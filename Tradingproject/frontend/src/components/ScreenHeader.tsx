import { Ionicons } from '@expo/vector-icons';
import { LinearGradient } from 'expo-linear-gradient';
import type { ReactNode } from 'react';
import { useMemo } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useTheme } from '../theme/ThemeContext';
import type { Theme } from '../theme/tokens';

export function ScreenHeader({
  eyebrow,
  title,
  right,
}: {
  eyebrow: string;
  title: string;
  right?: ReactNode;
}) {
  const { theme: colors, mode, setMode } = useTheme();
  const styles = useMemo(() => createStyles(colors), [colors]);

  return (
    <View style={styles.wrap}>
      <LinearGradient
        colors={[`${colors.accent}30`, `${colors.accent}00`]}
        start={{ x: 0, y: 0 }}
        end={{ x: 1, y: 1 }}
        style={styles.glow}
      />
      <View style={styles.row}>
        <View style={styles.titleCol}>
          <Text style={styles.eyebrow} numberOfLines={1}>
            {eyebrow}
          </Text>
          <Text style={styles.title} numberOfLines={1} ellipsizeMode="tail">
            {title}
          </Text>
        </View>
        <View style={styles.rightRow}>
          <Pressable
            onPress={() => setMode(mode === 'light' ? 'dark' : 'light')}
            style={styles.themeButton}
            hitSlop={8}
          >
            <Ionicons
              name={mode === 'light' ? 'moon-outline' : 'sunny-outline'}
              size={18}
              color={colors.textSecondary}
            />
          </Pressable>
          {right}
        </View>
      </View>
    </View>
  );
}

function createStyles(colors: Theme) {
  return StyleSheet.create({
    wrap: {
      marginHorizontal: -16,
      paddingHorizontal: 16,
      marginBottom: 4,
    },
    glow: {
      position: 'absolute',
      top: -20,
      left: -20,
      right: -20,
      height: 120,
      borderRadius: 60,
    },
    row: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      paddingTop: 4,
      paddingBottom: 4,
    },
    titleCol: {
      flex: 1,
      minWidth: 0,
      marginRight: 8,
    },
    eyebrow: {
      color: colors.accent,
      fontSize: 12,
      fontWeight: '700',
      letterSpacing: 1,
      marginBottom: 2,
    },
    title: {
      color: colors.textPrimary,
      fontSize: 26,
      fontWeight: '800',
      letterSpacing: -0.5,
    },
    rightRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 8,
    },
    themeButton: {
      width: 34,
      height: 34,
      borderRadius: 17,
      alignItems: 'center',
      justifyContent: 'center',
      backgroundColor: colors.surface,
      borderWidth: 1,
      borderColor: colors.border,
    },
  });
}

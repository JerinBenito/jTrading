import { useMemo } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { useTheme } from '../theme/ThemeContext';
import type { Theme } from '../theme/tokens';

export function LoadingState() {
  const { theme: colors } = useTheme();
  const styles = useMemo(() => createStyles(colors), [colors]);
  return (
    <View style={styles.center}>
      <ActivityIndicator color={colors.accent} />
    </View>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  const { theme: colors } = useTheme();
  const styles = useMemo(() => createStyles(colors), [colors]);
  return (
    <View style={styles.center}>
      <Text style={styles.errorText}>Couldn't reach the server.</Text>
      <Text style={styles.errorDetail}>{message}</Text>
      <Pressable style={styles.retryButton} onPress={onRetry}>
        <Text style={styles.retryText}>Retry</Text>
      </Pressable>
    </View>
  );
}

export function EmptyState({ message }: { message: string }) {
  const { theme: colors } = useTheme();
  const styles = useMemo(() => createStyles(colors), [colors]);
  return (
    <View style={styles.center}>
      <Text style={styles.emptyText}>{message}</Text>
    </View>
  );
}

function createStyles(colors: Theme) {
  return StyleSheet.create({
    center: {
      paddingVertical: 40,
      alignItems: 'center',
      gap: 8,
    },
    errorText: {
      color: colors.textPrimary,
      fontSize: 14,
      fontWeight: '600',
    },
    errorDetail: {
      color: colors.textMuted,
      fontSize: 12,
      textAlign: 'center',
      paddingHorizontal: 24,
    },
    retryButton: {
      marginTop: 8,
      backgroundColor: colors.accent,
      paddingHorizontal: 20,
      paddingVertical: 8,
      borderRadius: 10,
    },
    retryText: {
      color: '#fff',
      fontWeight: '600',
      fontSize: 13,
    },
    emptyText: {
      color: colors.textMuted,
      fontSize: 13,
    },
  });
}

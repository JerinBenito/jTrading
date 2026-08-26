import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../constants/colors';
import { useApiData } from '../hooks/useApiData';
import { api } from '../api/client';

export function HealthBadge() {
  const { data, loading, error } = useApiData(() => api.getHealth(), []);

  const isUp = !loading && !error && data?.status === 'UP';
  const dotColor = loading ? colors.textMuted : isUp ? colors.up : colors.down;
  const label = loading ? 'Checking…' : isUp ? 'System online' : 'System unreachable';

  return (
    <View style={styles.container}>
      <View style={[styles.dot, { backgroundColor: dotColor }]} />
      <Text style={styles.label}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 20,
  },
  dot: {
    width: 7,
    height: 7,
    borderRadius: 4,
  },
  label: {
    color: colors.textSecondary,
    fontSize: 11,
    fontWeight: '600',
  },
});

import { Pressable, StyleSheet, Text, View } from 'react-native';
import { INSTRUMENTS } from '../constants/config';
import { colors } from '../constants/colors';
import { useInstrument } from '../context/InstrumentContext';

export function InstrumentToggle() {
  const { instrument, setInstrument } = useInstrument();

  return (
    <View style={styles.container}>
      {INSTRUMENTS.map((option) => {
        const selected = option === instrument;
        return (
          <Pressable
            key={option}
            onPress={() => setInstrument(option)}
            style={[styles.pill, selected && styles.pillSelected]}
          >
            <Text style={[styles.label, selected && styles.labelSelected]}>{option}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    backgroundColor: colors.surface,
    borderRadius: 12,
    padding: 4,
    gap: 4,
    borderWidth: 1,
    borderColor: colors.border,
  },
  pill: {
    flex: 1,
    paddingVertical: 10,
    borderRadius: 9,
    alignItems: 'center',
  },
  pillSelected: {
    backgroundColor: colors.accent,
    shadowColor: colors.accent,
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.4,
    shadowRadius: 6,
    elevation: 3,
  },
  label: {
    color: colors.textSecondary,
    fontWeight: '600',
    fontSize: 14,
  },
  labelSelected: {
    color: colors.textPrimary,
  },
});

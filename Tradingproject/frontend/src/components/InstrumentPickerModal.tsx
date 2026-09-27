import { useMemo, useState } from 'react';
import { FlatList, Modal, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useTheme } from '../theme/ThemeContext';
import type { Theme } from '../theme/tokens';

const PINNED = ['NIFTY', 'BANKNIFTY'];

/** A searchable full-basket instrument picker — used where a screen needs to reach every NIFTY
 * 50 stock, not just the NIFTY/BANKNIFTY pair the app-wide InstrumentToggle covers. */
export function InstrumentPickerModal({
  visible,
  symbols,
  selected,
  onSelect,
  onClose,
}: {
  visible: boolean;
  symbols: string[];
  selected: string;
  onSelect: (symbol: string) => void;
  onClose: () => void;
}) {
  const { theme: colors } = useTheme();
  const styles = useMemo(() => createStyles(colors), [colors]);
  const [query, setQuery] = useState('');

  const ordered = useMemo(() => {
    const rest = symbols.filter((s) => !PINNED.includes(s)).sort((a, b) => a.localeCompare(b));
    const pinned = PINNED.filter((s) => symbols.includes(s));
    return [...pinned, ...rest];
  }, [symbols]);

  const filtered = useMemo(() => {
    const q = query.trim().toUpperCase();
    if (!q) return ordered;
    return ordered.filter((s) => s.includes(q));
  }, [ordered, query]);

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <Pressable style={styles.backdrop} onPress={onClose}>
        <Pressable style={styles.sheet} onPress={(e) => e.stopPropagation()}>
          <View style={styles.handle} />
          <Text style={styles.title}>Select instrument</Text>
          <View style={styles.searchRow}>
            <Ionicons name="search-outline" size={16} color={colors.textMuted} />
            <TextInput
              style={styles.searchInput}
              placeholder="Search symbol"
              placeholderTextColor={colors.textMuted}
              autoCapitalize="characters"
              autoCorrect={false}
              value={query}
              onChangeText={setQuery}
            />
          </View>
          <FlatList
            data={filtered}
            keyExtractor={(s) => s}
            style={styles.list}
            keyboardShouldPersistTaps="handled"
            renderItem={({ item }) => {
              const isSelected = item === selected;
              return (
                <Pressable
                  style={[styles.row, isSelected && styles.rowSelected]}
                  onPress={() => {
                    onSelect(item);
                    setQuery('');
                    onClose();
                  }}
                >
                  <Text style={[styles.rowText, isSelected && styles.rowTextSelected]} numberOfLines={1}>
                    {item}
                  </Text>
                  {isSelected && <Ionicons name="checkmark" size={18} color={colors.accent} />}
                </Pressable>
              );
            }}
            ListEmptyComponent={<Text style={styles.empty}>No matching symbol.</Text>}
          />
        </Pressable>
      </Pressable>
    </Modal>
  );
}

function createStyles(colors: Theme) {
  return StyleSheet.create({
    backdrop: {
      flex: 1,
      backgroundColor: 'rgba(0,0,0,0.5)',
      justifyContent: 'flex-end',
    },
    sheet: {
      backgroundColor: colors.surface,
      borderTopLeftRadius: 20,
      borderTopRightRadius: 20,
      paddingHorizontal: 16,
      paddingTop: 10,
      paddingBottom: 24,
      maxHeight: '75%',
      borderWidth: 1,
      borderColor: colors.border,
    },
    handle: {
      alignSelf: 'center',
      width: 36,
      height: 4,
      borderRadius: 2,
      backgroundColor: colors.border,
      marginBottom: 10,
    },
    title: {
      color: colors.textPrimary,
      fontSize: 16,
      fontWeight: '800',
      marginBottom: 10,
    },
    searchRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 8,
      backgroundColor: colors.surfaceAlt,
      borderRadius: 10,
      borderWidth: 1,
      borderColor: colors.border,
      paddingHorizontal: 10,
      paddingVertical: 8,
      marginBottom: 8,
    },
    searchInput: {
      flex: 1,
      color: colors.textPrimary,
      fontSize: 14,
      padding: 0,
    },
    list: {
      flexGrow: 0,
    },
    row: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingVertical: 12,
      paddingHorizontal: 4,
      borderBottomWidth: 1,
      borderBottomColor: colors.border,
    },
    rowSelected: {
      backgroundColor: colors.surfaceAlt,
    },
    rowText: {
      color: colors.textSecondary,
      fontSize: 14,
      fontWeight: '600',
    },
    rowTextSelected: {
      color: colors.textPrimary,
      fontWeight: '800',
    },
    empty: {
      color: colors.textMuted,
      fontSize: 13,
      textAlign: 'center',
      paddingVertical: 24,
    },
  });
}

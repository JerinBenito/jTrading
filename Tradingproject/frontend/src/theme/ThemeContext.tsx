import AsyncStorage from '@react-native-async-storage/async-storage';
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useColorScheme } from 'react-native';
import { darkTheme, lightTheme, type Theme } from './tokens';

export type ThemeMode = 'light' | 'dark';

const STORAGE_KEY = 'theme-mode-override';

interface ThemeContextValue {
  theme: Theme;
  mode: ThemeMode;
  /** Persists the override so it survives app restarts; pass null to go back to following the
   * phone's system appearance. */
  setMode: (mode: ThemeMode | null) => void;
}

const ThemeContext = createContext<ThemeContextValue | undefined>(undefined);

export function ThemeProvider({ children }: { children: ReactNode }) {
  const systemScheme = useColorScheme();
  const [override, setOverride] = useState<ThemeMode | null>(null);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    AsyncStorage.getItem(STORAGE_KEY)
      .then((stored) => {
        if (stored === 'light' || stored === 'dark') {
          setOverride(stored);
        }
      })
      .finally(() => setHydrated(true));
  }, []);

  const mode: ThemeMode = override ?? (systemScheme === 'light' ? 'light' : 'dark');

  const setMode = (next: ThemeMode | null) => {
    setOverride(next);
    if (next) {
      AsyncStorage.setItem(STORAGE_KEY, next).catch(() => {});
    } else {
      AsyncStorage.removeItem(STORAGE_KEY).catch(() => {});
    }
  };

  const value = useMemo<ThemeContextValue>(
    () => ({ theme: mode === 'light' ? lightTheme : darkTheme, mode, setMode }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [mode]
  );

  // Avoid a flash of the wrong theme before the persisted override loads — render nothing for
  // the one frame this takes rather than briefly showing the system-default theme.
  if (!hydrated) {
    return null;
  }

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  const ctx = useContext(ThemeContext);
  if (!ctx) {
    throw new Error('useTheme must be used within a ThemeProvider');
  }
  return ctx;
}

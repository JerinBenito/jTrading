import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { InstrumentProvider } from '../src/context/InstrumentContext';
import { ThemeProvider, useTheme } from '../src/theme/ThemeContext';

function RootStack() {
  const { theme, mode } = useTheme();
  return (
    <>
      <StatusBar style={mode === 'dark' ? 'light' : 'dark'} />
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: theme.background } }}>
        <Stack.Screen name="(tabs)" />
        <Stack.Screen name="stock/[symbol]" />
      </Stack>
    </>
  );
}

export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <ThemeProvider>
        <InstrumentProvider>
          <RootStack />
        </InstrumentProvider>
      </ThemeProvider>
    </SafeAreaProvider>
  );
}

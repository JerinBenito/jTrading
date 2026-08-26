import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { InstrumentProvider } from '../src/context/InstrumentContext';
import { colors } from '../src/constants/colors';

export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <InstrumentProvider>
        <StatusBar style="light" />
        <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.background } }}>
          <Stack.Screen name="(tabs)" />
        </Stack>
      </InstrumentProvider>
    </SafeAreaProvider>
  );
}

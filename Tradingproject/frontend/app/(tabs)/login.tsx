import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { WebView, type WebViewNavigation } from 'react-native-webview';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../../src/constants/colors';
import { useApiData } from '../../src/hooks/useApiData';
import { api } from '../../src/api/client';
import { ErrorState, LoadingState } from '../../src/components/ScreenState';

export default function LoginScreen() {
  const insets = useSafeAreaInsets();
  const loginUrlQuery = useApiData(() => api.getLoginUrl(), []);
  const [status, setStatus] = useState<'idle' | 'success'>('idle');

  const handleNavigationChange = (navState: WebViewNavigation) => {
    if (navState.url.includes('/auth/upstox/callback')) {
      setStatus('success');
    }
  };

  const startOver = () => {
    setStatus('idle');
    loginUrlQuery.refresh();
  };

  return (
    <View style={[styles.screen, { paddingTop: insets.top }]}>
      <View style={styles.header}>
        <Text style={styles.title}>Upstox login</Text>
        <Text style={styles.subtitle}>
          Tokens expire daily — log in each trading morning to keep the pipeline running.
        </Text>
      </View>

      {status === 'success' ? (
        <View style={styles.successBox}>
          <Ionicons name="checkmark-circle" size={48} color={colors.up} />
          <Text style={styles.successText}>Logged in for today</Text>
          <Pressable style={styles.button} onPress={startOver}>
            <Text style={styles.buttonText}>Log in again</Text>
          </Pressable>
        </View>
      ) : loginUrlQuery.loading ? (
        <LoadingState />
      ) : loginUrlQuery.error ? (
        <ErrorState message={loginUrlQuery.error} onRetry={loginUrlQuery.refresh} />
      ) : loginUrlQuery.data ? (
        <WebView
          source={{ uri: loginUrlQuery.data.loginUrl }}
          onNavigationStateChange={handleNavigationChange}
          style={styles.webview}
          startInLoadingState
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.background,
  },
  header: {
    padding: 16,
    gap: 4,
  },
  title: {
    color: colors.textPrimary,
    fontSize: 22,
    fontWeight: '700',
  },
  subtitle: {
    color: colors.textMuted,
    fontSize: 12,
  },
  webview: {
    flex: 1,
    backgroundColor: colors.background,
  },
  successBox: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 12,
  },
  successText: {
    color: colors.textPrimary,
    fontSize: 16,
    fontWeight: '600',
  },
  button: {
    marginTop: 8,
    backgroundColor: colors.accent,
    paddingHorizontal: 20,
    paddingVertical: 10,
    borderRadius: 10,
  },
  buttonText: {
    color: '#fff',
    fontWeight: '600',
  },
});

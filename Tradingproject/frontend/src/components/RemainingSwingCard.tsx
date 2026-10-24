import { useMemo } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useTheme } from '../theme/ThemeContext';
import type { Theme } from '../theme/tokens';
import type { IntradayRiskForecast } from '../api/types';
import { Card } from './ui/Card';

const HOUR_LABELS = ['10:15', '11:15', '12:15', '13:15', '14:15', '15:15'];

/** "How far can it still move today": the newest same-day swing forecast. Pure risk context - says nothing about direction. */
export function RemainingSwingCard({ forecasts }: { forecasts: IntradayRiskForecast[] }) {
  const { theme: colors } = useTheme();
  const styles = useMemo(() => createStyles(colors), [colors]);
  if (forecasts.length === 0) return null;
  const f = [...forecasts].sort((a, b) => b.hoursSinceOpen - a.hoursSinceOpen)[0];
  const price = f.currentPrice;
  const rupees = (pct: number) => (price ? ` (about ₹${((price * pct) / 100).toFixed(price >= 1000 ? 0 : 1)})` : '');
  const at = HOUR_LABELS[f.hoursSinceOpen] ?? '';
  const asOf = new Date(f.predictedAt).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Kolkata' });

  return (
    <Card title="How far can it still move today?" subtitle={`Forecast made around ${at} (updated ${asOf} IST) — about size, not direction`}>
      <View style={styles.row}>
        <View style={styles.tile}>
          <Text style={styles.label}>Expected swing left</Text>
          <Text style={styles.value}>{f.expectedSwingPct.toFixed(2)}%</Text>
          <Text style={styles.sub}>{rupees(f.expectedSwingPct).trim()}</Text>
        </View>
        <View style={styles.tile}>
          <Text style={styles.label}>Rough-day level</Text>
          <Text style={[styles.value, { color: colors.neutral }]}>{f.upper90SwingPct.toFixed(2)}%</Text>
          <Text style={styles.sub}>{rupees(f.upper90SwingPct).trim()}</Text>
        </View>
      </View>
      <Text style={styles.body}>
        From here to the close, the gap between today's highest and lowest price from now on is expected to be about {f.expectedSwingPct.toFixed(2)}% of the price, and
        it stayed under {f.upper90SwingPct.toFixed(2)}% on about 9 days in 10 in testing (600 days, held 90.5%). It says nothing about whether the price goes up or down.
      </Text>
    </Card>
  );
}

function createStyles(colors: Theme) {
  return StyleSheet.create({
    row: { flexDirection: 'row', gap: 10, marginTop: 10 },
    tile: { flex: 1, backgroundColor: colors.surfaceAlt, borderRadius: 12, padding: 12 },
    label: { color: colors.textMuted, fontSize: 10, fontWeight: '700', textTransform: 'uppercase' },
    value: { color: colors.textPrimary, fontSize: 22, fontWeight: '800', marginTop: 4 },
    sub: { color: colors.textSecondary, fontSize: 11, marginTop: 2 },
    body: { color: colors.textSecondary, fontSize: 12, lineHeight: 18, marginTop: 12 },
  });
}

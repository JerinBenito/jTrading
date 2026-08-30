import { Pressable, StyleSheet, Text, View } from 'react-native';
import { colors } from '../constants/colors';
import type { BasketSnapshot } from '../api/types';

function trendColor(trend: BasketSnapshot['trend']) {
  if (trend === 'BULLISH') return colors.up;
  if (trend === 'BEARISH') return colors.down;
  return colors.textMuted;
}

function rsiZoneColor(zone: BasketSnapshot['rsiZone']) {
  if (zone === 'OVERBOUGHT') return colors.down;
  if (zone === 'OVERSOLD') return colors.up;
  return colors.textMuted;
}

export function BasketSnapshotRow({ snapshot, onPress }: { snapshot: BasketSnapshot; onPress?: () => void }) {
  const changeColor = snapshot.changePct >= 0 ? colors.up : colors.down;
  const hasPrediction = snapshot.deviationFromPredictionPct !== null;
  const deviationColor = hasPrediction
    ? snapshot.deviationFromPredictionPct! >= 0
      ? colors.up
      : colors.down
    : colors.textMuted;

  return (
    <Pressable style={styles.row} onPress={onPress}>
      <View style={styles.left}>
        <Text style={styles.symbol}>{snapshot.symbol}</Text>
        <Text style={styles.close}>{snapshot.lastClose.toFixed(2)}</Text>
      </View>
      <View style={styles.mid}>
        <Text style={[styles.trendBadge, { color: trendColor(snapshot.trend) }]}>{snapshot.trend}</Text>
        {snapshot.rsi14 !== null && (
          <Text style={[styles.rsiBadge, { color: rsiZoneColor(snapshot.rsiZone) }]}>
            RSI {snapshot.rsi14.toFixed(0)}
          </Text>
        )}
      </View>
      <View style={styles.right}>
        <Text style={[styles.change, { color: changeColor }]}>
          {snapshot.changePct >= 0 ? '+' : ''}
          {snapshot.changePct.toFixed(2)}%
        </Text>
        <Text style={[styles.deviation, { color: deviationColor }]}>
          {hasPrediction
            ? `${snapshot.deviationFromPredictionPct! >= 0 ? '+' : ''}${snapshot.deviationFromPredictionPct!.toFixed(2)}% vs call`
            : 'no call yet'}
        </Text>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.surface,
    borderRadius: 14,
    padding: 14,
    borderWidth: 1,
    borderColor: colors.border,
  },
  left: {
    flex: 1.2,
    gap: 2,
  },
  symbol: {
    color: colors.textPrimary,
    fontSize: 14,
    fontWeight: '700',
  },
  close: {
    color: colors.textMuted,
    fontSize: 11,
  },
  mid: {
    flex: 1,
    alignItems: 'flex-start',
    gap: 2,
  },
  trendBadge: {
    fontSize: 11,
    fontWeight: '700',
  },
  rsiBadge: {
    fontSize: 11,
    fontWeight: '600',
  },
  right: {
    flex: 0.9,
    alignItems: 'flex-end',
    gap: 2,
  },
  change: {
    fontSize: 14,
    fontWeight: '700',
  },
  deviation: {
    fontSize: 10,
    fontWeight: '600',
  },
});

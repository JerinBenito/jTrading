import { View } from 'react-native';
import { colors } from '../constants/colors';
import { Card } from './ui/Card';
import { StatTile } from './ui/StatTile';
import type { GlobalMarketSnapshot } from '../api/types';

const DISPLAY_ORDER = ['SP500', 'DOW', 'NASDAQ', 'CRUDE_OIL', 'USD_INR'];
const LABELS: Record<string, string> = {
  SP500: 'S&P 500',
  DOW: 'Dow Jones',
  NASDAQ: 'Nasdaq',
  CRUDE_OIL: 'Crude Oil',
  USD_INR: 'USD/INR',
};

function formatPrice(value: number) {
  return value.toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

function formatDelta(pct: number | null) {
  if (pct === null) return undefined;
  return `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`;
}

/** Overnight global-market context (US indices, crude oil, USD/INR) — purely observational,
 * not wired into any prediction yet. Shown so it's visible while it accumulates real history. */
export function GlobalMarketCard({ snapshots }: { snapshots: GlobalMarketSnapshot[] }) {
  if (snapshots.length === 0) {
    return null;
  }
  const bySymbol = new Map(snapshots.map((s) => [s.symbol, s]));

  return (
    <Card title="Global markets" subtitle="Overnight read, captured before today's session opened.">
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {DISPLAY_ORDER.map((symbol) => {
          const snapshot = bySymbol.get(symbol);
          if (!snapshot) return null;
          const delta = formatDelta(snapshot.changePct);
          return (
            <StatTile
              key={symbol}
              label={LABELS[symbol] ?? symbol}
              value={formatPrice(snapshot.price)}
              deltaText={delta}
              deltaColor={snapshot.changePct === null ? undefined : snapshot.changePct >= 0 ? colors.up : colors.down}
            />
          );
        })}
      </View>
    </Card>
  );
}

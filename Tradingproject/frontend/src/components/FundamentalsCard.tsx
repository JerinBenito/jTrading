import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../constants/colors';
import { Card } from './ui/Card';
import type { CompanyFundamental } from '../api/types';

/** Company fundamentals (P/E, P/B, ROA, ROE, ROCE, EV/EBITDA, Quick Ratio) vs. sector averages —
 * from Upstox's own data, refreshed daily. Basket stocks only; NIFTY/BANKNIFTY are indices, not
 * companies, so this renders nothing for them. Purely observational, not wired into any
 * prediction — shown as reference context, not a "this stock is cheap/expensive" verdict, since
 * whether a given ratio being higher or lower is actually good depends on the sector and isn't
 * something to assert here. */
export function FundamentalsCard({ ratios }: { ratios: CompanyFundamental[] }) {
  if (ratios.length === 0) {
    return null;
  }

  return (
    <Card title="Fundamentals" subtitle="This stock vs. its sector average.">
      <View style={styles.headerRow}>
        <Text style={styles.headerSpacer} />
        <Text style={styles.columnHeader}>This stock</Text>
        <Text style={styles.columnHeader}>Sector avg</Text>
      </View>
      {ratios.map((ratio) => (
        <View key={ratio.ratioName} style={styles.row}>
          <Text style={styles.ratioName} numberOfLines={1}>
            {ratio.ratioName}
          </Text>
          <Text style={styles.companyValue} numberOfLines={1}>
            {ratio.companyValue ?? '—'}
          </Text>
          <Text style={styles.sectorValue} numberOfLines={1}>
            {ratio.sectorValue ?? '—'}
          </Text>
        </View>
      ))}
    </Card>
  );
}

const styles = StyleSheet.create({
  headerRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  headerSpacer: {
    flex: 1.1,
  },
  columnHeader: {
    flex: 1,
    color: colors.textMuted,
    fontSize: 10,
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: 0.4,
    textAlign: 'right',
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 8,
    borderTopWidth: 1,
    borderTopColor: colors.border,
  },
  ratioName: {
    flex: 1.1,
    color: colors.textSecondary,
    fontSize: 13,
    fontWeight: '600',
  },
  companyValue: {
    flex: 1,
    color: colors.textPrimary,
    fontSize: 13,
    fontWeight: '700',
    textAlign: 'right',
  },
  sectorValue: {
    flex: 1,
    color: colors.textMuted,
    fontSize: 13,
    textAlign: 'right',
  },
});

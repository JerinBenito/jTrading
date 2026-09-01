import { Ionicons } from '@expo/vector-icons';
import { StyleSheet, Text, View } from 'react-native';
import Svg, {
  Circle,
  Defs,
  Line,
  LinearGradient,
  Path,
  Rect,
  Stop,
  Text as SvgText,
} from 'react-native-svg';
import { colors } from '../constants/colors';
import type { DailyTrajectory } from '../api/types';
import { areaPathD, smoothPathD } from '../utils/smoothPath';

const CHART_HEIGHT = 200;
const PADDING_X = 12;
const PADDING_TOP = 28;
const PADDING_BOTTOM = 28;
const CHART_WIDTH = 320;
const LIVE_BAND_WIDTH = 34;

function formatPrice(value: number, compact = false) {
  if (compact) {
    return value.toLocaleString('en-IN', { maximumFractionDigits: 0 });
  }
  return value.toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

function formatHour(iso: string) {
  return new Date(iso).toLocaleString('en-IN', {
    timeZone: 'Asia/Kolkata',
    hour: 'numeric',
    hour12: true,
  });
}

export function TrajectoryChart({
  trajectory,
  aiPredictedClose,
  liveLtp,
  liveConnected,
}: {
  trajectory: DailyTrajectory;
  /** The AI model's predicted close for this same day, if one exists — drawn as a second
   * reference line so it's visually comparable against the deterministic model's line. */
  aiPredictedClose?: number | null;
  /** Raw tick from the real Upstox WebSocket feed (useLiveFeed) — distinct from
   * currentEstimatedClose, which is the deterministic model's own re-anchored estimate. */
  liveLtp?: number | null;
  liveConnected?: boolean;
}) {
  const {
    points,
    predictedClose,
    rangeLow,
    rangeHigh,
    currentEstimatedClose,
    currentEstimatedRangeLow,
    currentEstimatedRangeHigh,
  } = trajectory;

  if (points.length === 0) {
    return (
      <View style={styles.card}>
        <Text style={styles.title}>Today's trajectory</Text>
        <Text style={styles.empty}>No hourly data yet today.</Text>
      </View>
    );
  }

  const hasLiveEstimate = currentEstimatedClose !== null;
  const hasAiPrediction = aiPredictedClose !== null && aiPredictedClose !== undefined;

  const plotWidth = CHART_WIDTH - PADDING_X * 2;
  const plotHeight = CHART_HEIGHT - PADDING_TOP - PADDING_BOTTOM;

  const allValues = [
    rangeLow,
    rangeHigh,
    predictedClose,
    ...points.map((p) => p.actualClose),
    ...(hasLiveEstimate ? [currentEstimatedRangeLow!, currentEstimatedRangeHigh!] : []),
    ...(hasAiPrediction ? [aiPredictedClose!] : []),
  ];
  const min = Math.min(...allValues);
  const max = Math.max(...allValues);
  const span = max - min || 1;

  const xFor = (index: number) =>
    points.length === 1
      ? PADDING_X + plotWidth / 2
      : PADDING_X + (index / (points.length - 1)) * plotWidth;
  const yFor = (value: number) => PADDING_TOP + (1 - (value - min) / span) * plotHeight;

  const linePoints = points.map((p, i) => ({ x: xFor(i), y: yFor(p.actualClose) }));
  const lastPoint = points[points.length - 1];
  const lastUp = lastPoint.actualClose >= predictedClose;
  const lineColor = lastUp ? colors.up : colors.down;
  const gradientId = lastUp ? 'trajGradientUp' : 'trajGradientDown';

  const gridLines = [min + span * 0.25, min + span * 0.5, min + span * 0.75];
  const liveDeltaPct = hasLiveEstimate
    ? ((currentEstimatedClose! - predictedClose) / predictedClose) * 100
    : 0;

  return (
    <View style={styles.card}>
      <View style={styles.headerRow}>
        <Text style={styles.title}>Today's trajectory</Text>
        <View style={styles.headerRightRow}>
          {liveConnected && (
            <View style={styles.liveTickPill}>
              <View style={styles.liveTickDot} />
              <Text style={styles.liveTickText}>
                {liveLtp !== null && liveLtp !== undefined ? formatPrice(liveLtp) : 'live'}
              </Text>
            </View>
          )}
          <View style={styles.deviationPill}>
            <Text style={[styles.deviationText, { color: lineColor }]}>
              {lastPoint.deviationPct >= 0 ? '+' : ''}
              {lastPoint.deviationPct.toFixed(2)}%
            </Text>
          </View>
        </View>
      </View>

      {hasLiveEstimate && (
        <View style={styles.liveCard}>
          <View style={styles.liveHeaderRow}>
            <View style={styles.liveDotRow}>
              <View style={styles.liveDot} />
              <Text style={styles.liveLabel}>Live re-anchored estimate</Text>
            </View>
            <Text style={[styles.liveDelta, { color: liveDeltaPct >= 0 ? colors.up : colors.down }]}>
              {liveDeltaPct >= 0 ? '+' : ''}
              {liveDeltaPct.toFixed(2)}% vs morning call
            </Text>
          </View>
          <Text style={styles.liveValue}>{formatPrice(currentEstimatedClose!)}</Text>
          <Text style={styles.liveRange}>
            narrowing range {formatPrice(currentEstimatedRangeLow!)} – {formatPrice(currentEstimatedRangeHigh!)}
          </Text>
        </View>
      )}

      <Svg width={CHART_WIDTH} height={CHART_HEIGHT}>
        <Defs>
          <LinearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
            <Stop offset="0" stopColor={lineColor} stopOpacity={0.32} />
            <Stop offset="1" stopColor={lineColor} stopOpacity={0} />
          </LinearGradient>
        </Defs>

        {gridLines.map((value) => (
          <Line
            key={value}
            x1={PADDING_X}
            y1={yFor(value)}
            x2={CHART_WIDTH - PADDING_X}
            y2={yFor(value)}
            stroke={colors.border}
            strokeWidth={1}
          />
        ))}

        <Rect
          x={PADDING_X}
          y={yFor(rangeHigh)}
          width={plotWidth}
          height={Math.max(yFor(rangeLow) - yFor(rangeHigh), 0)}
          fill={colors.surfaceAlt}
          opacity={0.7}
          rx={4}
        />

        {hasLiveEstimate && (
          <Rect
            x={CHART_WIDTH - PADDING_X - LIVE_BAND_WIDTH}
            y={yFor(currentEstimatedRangeHigh!)}
            width={LIVE_BAND_WIDTH}
            height={Math.max(yFor(currentEstimatedRangeLow!) - yFor(currentEstimatedRangeHigh!), 0)}
            fill={colors.accent}
            opacity={0.28}
            rx={4}
          />
        )}

        <Line
          x1={PADDING_X}
          y1={yFor(predictedClose)}
          x2={CHART_WIDTH - PADDING_X}
          y2={yFor(predictedClose)}
          stroke={colors.textMuted}
          strokeWidth={1.25}
          strokeDasharray="5,4"
        />

        {hasAiPrediction && (
          <Line
            x1={PADDING_X}
            y1={yFor(aiPredictedClose!)}
            x2={CHART_WIDTH - PADDING_X}
            y2={yFor(aiPredictedClose!)}
            stroke={colors.ai}
            strokeWidth={1.25}
            strokeDasharray="2,3"
          />
        )}

        <Path d={areaPathD(linePoints, CHART_HEIGHT - PADDING_BOTTOM)} fill={`url(#${gradientId})`} />
        <Path d={smoothPathD(linePoints)} fill="none" stroke={lineColor} strokeWidth={2.75} strokeLinecap="round" />

        {linePoints.map((p, i) => {
          const isLast = i === linePoints.length - 1;
          return (
            <Circle
              key={points[i].ts}
              cx={p.x}
              cy={p.y}
              r={isLast ? 4.5 : 2.5}
              fill={isLast ? lineColor : colors.background}
              stroke={lineColor}
              strokeWidth={isLast ? 0 : 1.5}
            />
          );
        })}

        <SvgText x={PADDING_X} y={16} fill={colors.textMuted} fontSize={10} fontWeight="600">
          {formatPrice(max, true)}
        </SvgText>
        <SvgText
          x={PADDING_X}
          y={CHART_HEIGHT - 8}
          fill={colors.textMuted}
          fontSize={10}
          fontWeight="600"
        >
          {formatPrice(min, true)}
        </SvgText>
        <SvgText
          x={CHART_WIDTH - PADDING_X}
          y={yFor(predictedClose) - 6}
          fill={colors.textMuted}
          fontSize={10}
          textAnchor="end"
        >
          predicted {formatPrice(predictedClose, true)}
        </SvgText>

        {hasAiPrediction && (
          <SvgText
            x={CHART_WIDTH - PADDING_X}
            y={yFor(aiPredictedClose!) - 6}
            fill={colors.ai}
            fontSize={10}
            textAnchor="end"
          >
            AI {formatPrice(aiPredictedClose!, true)}
          </SvgText>
        )}

        <SvgText x={xFor(0)} y={CHART_HEIGHT - 8} fill={colors.textMuted} fontSize={10} textAnchor="start">
          {formatHour(points[0].ts)}
        </SvgText>
        <SvgText
          x={xFor(points.length - 1)}
          y={CHART_HEIGHT - 8}
          fill={colors.textMuted}
          fontSize={10}
          textAnchor="end"
        >
          {formatHour(lastPoint.ts)}
        </SvgText>
      </Svg>

      <View style={styles.legendRow}>
        <LegendItem colorSwatch={lineColor} label="Actual price" />
        <LegendItem dashed label="Morning prediction" />
        <LegendItem boxSwatch={colors.surfaceAlt} label="Morning range" />
        {hasLiveEstimate && <LegendItem boxSwatch={colors.accent} label="Live range" />}
        {hasAiPrediction && <LegendItem dashed dashColor={colors.ai} label="AI prediction" />}
      </View>
    </View>
  );
}

function LegendItem({
  colorSwatch,
  boxSwatch,
  dashed,
  dashColor,
  label,
}: {
  colorSwatch?: string;
  boxSwatch?: string;
  dashed?: boolean;
  dashColor?: string;
  label: string;
}) {
  return (
    <View style={styles.legendItem}>
      {colorSwatch && <View style={[styles.legendDot, { backgroundColor: colorSwatch }]} />}
      {boxSwatch && <View style={[styles.legendBox, { backgroundColor: boxSwatch, opacity: boxSwatch === colors.accent ? 0.5 : 1 }]} />}
      {dashed && <View style={[styles.legendDash, dashColor ? { borderColor: dashColor } : null]} />}
      <Text style={styles.legendLabel}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.surface,
    borderRadius: 18,
    padding: 16,
    gap: 10,
    borderWidth: 1,
    borderColor: colors.border,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.18,
    shadowRadius: 10,
    elevation: 3,
  },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  title: {
    color: colors.textSecondary,
    fontSize: 13,
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: 0.6,
  },
  headerRightRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  liveTickPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    backgroundColor: 'rgba(62, 207, 142, 0.14)',
    paddingHorizontal: 7,
    paddingVertical: 3,
    borderRadius: 8,
  },
  liveTickDot: {
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: colors.up,
  },
  liveTickText: {
    color: colors.up,
    fontSize: 11,
    fontWeight: '700',
  },
  deviationPill: {
    backgroundColor: colors.surfaceAlt,
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 8,
  },
  deviationText: {
    fontSize: 12,
    fontWeight: '700',
  },
  empty: {
    color: colors.textMuted,
    fontSize: 13,
  },
  liveCard: {
    backgroundColor: 'rgba(91, 140, 255, 0.1)',
    borderRadius: 14,
    borderWidth: 1,
    borderColor: 'rgba(91, 140, 255, 0.35)',
    padding: 12,
    gap: 3,
  },
  liveHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  liveDotRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  liveDot: {
    width: 7,
    height: 7,
    borderRadius: 4,
    backgroundColor: colors.accent,
  },
  liveLabel: {
    color: colors.accent,
    fontSize: 11,
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: 0.4,
  },
  liveDelta: {
    fontSize: 11,
    fontWeight: '700',
  },
  liveValue: {
    color: colors.textPrimary,
    fontSize: 24,
    fontWeight: '800',
    letterSpacing: -0.5,
  },
  liveRange: {
    color: colors.textSecondary,
    fontSize: 12,
  },
  legendRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 16,
    marginTop: 2,
  },
  legendItem: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
  },
  legendDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  legendBox: {
    width: 10,
    height: 8,
    borderRadius: 2,
  },
  legendDash: {
    width: 10,
    height: 0,
    borderTopWidth: 1.5,
    borderStyle: 'dashed',
    borderColor: colors.textMuted,
  },
  legendLabel: {
    color: colors.textMuted,
    fontSize: 11,
  },
});

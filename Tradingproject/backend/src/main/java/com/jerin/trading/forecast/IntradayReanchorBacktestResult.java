package com.jerin.trading.forecast;

import java.util.List;

public record IntradayReanchorBacktestResult(
        List<HourBucketComparison> byHour,
        BigMorningMissComparison bigMorningMiss
) {
}

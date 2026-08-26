package com.jerin.trading.indicator;

import com.jerin.trading.domain.OptionChainSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PcrCalculatorTest {

    @Test
    void sumsOiByOptionTypeAndDivides() {
        List<OptionChainSnapshot> rows = List.of(
                leg("CE", 1000L), leg("CE", 500L),   // total call OI = 1500
                leg("PE", 900L), leg("PE", 600L));    // total put OI = 1500

        BigDecimal pcr = PcrCalculator.calculate(rows);

        assertThat(pcr).isEqualByComparingTo("1.0000");
    }

    @Test
    void nullOiTreatedAsZero() {
        List<OptionChainSnapshot> rows = List.of(
                leg("CE", 1000L),
                leg("PE", null),
                leg("PE", 500L));

        BigDecimal pcr = PcrCalculator.calculate(rows);

        assertThat(pcr).isEqualByComparingTo("0.5000");
    }

    @Test
    void zeroCallOiReturnsNullRatherThanDividingByZero() {
        List<OptionChainSnapshot> rows = List.of(leg("PE", 500L));

        assertThat(PcrCalculator.calculate(rows)).isNull();
    }

    private static OptionChainSnapshot leg(String optionType, Long oi) {
        return OptionChainSnapshot.builder().optionType(optionType).oi(oi).build();
    }
}

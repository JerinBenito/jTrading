package com.jerin.trading.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

public class PatternStatsId implements Serializable {

    private String patternId;
    private String instrument;
    private LocalDate windowEnd;

    public PatternStatsId() {
    }

    public PatternStatsId(String patternId, String instrument, LocalDate windowEnd) {
        this.patternId = patternId;
        this.instrument = instrument;
        this.windowEnd = windowEnd;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PatternStatsId that)) return false;
        return Objects.equals(patternId, that.patternId)
                && Objects.equals(instrument, that.instrument)
                && Objects.equals(windowEnd, that.windowEnd);
    }

    @Override
    public int hashCode() {
        return Objects.hash(patternId, instrument, windowEnd);
    }
}

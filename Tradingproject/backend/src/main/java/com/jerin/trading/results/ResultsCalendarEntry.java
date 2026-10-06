package com.jerin.trading.results;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One quarterly-results date for one basket stock — see {@link ResultsCalendarService}. */
@Entity
@Table(name = "results_calendar", uniqueConstraints = @UniqueConstraint(columnNames = {"instrument", "results_date"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResultsCalendarEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String instrument;

    @Column(name = "results_date", nullable = false)
    private LocalDate resultsDate;

    @Column(name = "eps_estimate")
    private BigDecimal epsEstimate;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;
}

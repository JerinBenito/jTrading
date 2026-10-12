package com.jerin.trading.screen;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One end-of-day screen (JSON text) for one trading date - see {@link DailyScreenService}. */
@Entity
@Table(name = "daily_screens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyScreen {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "screen_date", nullable = false, unique = true)
    private LocalDate screenDate;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}

package com.jerin.trading.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "signal_predictions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SignalPrediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String instrument;

    @Column(nullable = false)
    private OffsetDateTime ts;

    @Column(name = "pattern_id", nullable = false, length = 50)
    private String patternId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inputs_snapshot", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> inputsSnapshot;

    @Column(name = "predicted_direction", nullable = false, length = 10)
    private String predictedDirection;

    @Column(name = "confidence_tier", nullable = false, length = 10)
    private String confidenceTier;

    @Column(name = "sample_size_at_time", nullable = false)
    private Integer sampleSizeAtTime;
}

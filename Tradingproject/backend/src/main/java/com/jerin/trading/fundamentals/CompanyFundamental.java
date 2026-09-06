package com.jerin.trading.fundamentals;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/** One financial ratio's current known value for one basket stock — see
 * {@link CompanyFundamentalService}. Upserted in place, not a changelog. */
@Entity
@Table(name = "company_fundamental", uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "ratio_name"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyFundamental {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 20)
    private String isin;

    @Column(name = "ratio_name", nullable = false, length = 50)
    private String ratioName;

    @Column(name = "company_value", length = 20)
    private String companyValue;

    @Column(name = "sector_value", length = 20)
    private String sectorValue;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;
}

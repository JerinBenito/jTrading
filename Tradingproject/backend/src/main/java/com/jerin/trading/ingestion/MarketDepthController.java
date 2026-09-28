package com.jerin.trading.ingestion;

import com.jerin.trading.domain.MarketDepthSnapshot;
import com.jerin.trading.repository.MarketDepthSnapshotRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Manual trigger + read access for market depth capture — the scheduled job does this
 * automatically every cycle; this is for testing on demand and inspecting what's accumulated. */
@RestController
@RequestMapping("/api/market-depth")
public class MarketDepthController {

    private final MarketDepthIngestionService marketDepthIngestionService;
    private final MarketDepthSnapshotRepository repository;

    public MarketDepthController(MarketDepthIngestionService marketDepthIngestionService,
                                  MarketDepthSnapshotRepository repository) {
        this.marketDepthIngestionService = marketDepthIngestionService;
        this.repository = repository;
    }

    @PostMapping("/capture")
    public Map<String, Integer> capture() {
        return Map.of("captured", marketDepthIngestionService.ingestBasketSnapshot());
    }

    @GetMapping("/recent")
    public List<MarketDepthSnapshot> recent() {
        return repository.findAll();
    }
}

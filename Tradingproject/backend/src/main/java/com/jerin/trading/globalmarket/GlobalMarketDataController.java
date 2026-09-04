package com.jerin.trading.globalmarket;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Overnight global-market context — see {@link GlobalMarketDataService}. */
@RestController
@RequestMapping("/api/global-market")
public class GlobalMarketDataController {

    private final GlobalMarketDataService service;

    public GlobalMarketDataController(GlobalMarketDataService service) {
        this.service = service;
    }

    /** Manual trigger — the scheduled job does this automatically each morning; exposed for testing on demand. */
    @PostMapping("/fetch")
    public Map<String, Object> fetch() {
        return Map.of("saved", service.fetchToday());
    }

    /** Most recent known snapshot per symbol. */
    @GetMapping("/latest")
    public List<GlobalMarketSnapshot> latest() {
        return service.latest();
    }
}

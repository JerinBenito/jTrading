package com.jerin.trading.fundamentals;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Company fundamentals for the NIFTY 50 basket — see {@link CompanyFundamentalService}. */
@RestController
@RequestMapping("/api/fundamentals")
public class CompanyFundamentalController {

    private final CompanyFundamentalService service;

    public CompanyFundamentalController(CompanyFundamentalService service) {
        this.service = service;
    }

    @GetMapping("/{symbol}")
    public List<CompanyFundamental> forSymbol(@PathVariable String symbol) {
        return service.forSymbol(symbol);
    }

    /** Manual trigger — the scheduled job does this automatically each morning; exposed for testing on demand. */
    @PostMapping("/{symbol}/fetch")
    public Map<String, Object> fetchOne(@PathVariable String symbol) {
        return Map.of("symbol", symbol, "ratiosSaved", service.fetchForSymbol(symbol));
    }

    @PostMapping("/fetch-all")
    public Map<String, Object> fetchAll() {
        return Map.of("ratiosSaved", service.fetchAllBasketSymbols());
    }
}

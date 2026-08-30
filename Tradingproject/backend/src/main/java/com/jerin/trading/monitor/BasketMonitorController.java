package com.jerin.trading.monitor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only monitoring view across NIFTY, BANKNIFTY, and the NIFTY 50 basket — no signals, nothing persisted. */
@RestController
@RequestMapping("/api/monitor")
public class BasketMonitorController {

    private final BasketMonitorService basketMonitorService;

    public BasketMonitorController(BasketMonitorService basketMonitorService) {
        this.basketMonitorService = basketMonitorService;
    }

    @GetMapping("/basket")
    public List<BasketSnapshot> basket() {
        return basketMonitorService.snapshot();
    }
}

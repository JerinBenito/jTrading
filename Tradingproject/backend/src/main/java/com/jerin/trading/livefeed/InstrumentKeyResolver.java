package com.jerin.trading.livefeed;

import com.jerin.trading.ingestion.EquityBasketIngestionService;
import com.jerin.trading.ingestion.Instrument;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Maps this app's own instrument tags ("NIFTY", "SBILIFE", ...) to Upstox's real instrument_key
 * ("NSE_INDEX|Nifty 50", "NSE_EQ|INE...") — the live feed subscribes by instrument_key, not by
 * our tag, so every consumer needs this translation. */
@Component
public class InstrumentKeyResolver {

    private final EquityBasketIngestionService equityBasketIngestionService;

    public InstrumentKeyResolver(EquityBasketIngestionService equityBasketIngestionService) {
        this.equityBasketIngestionService = equityBasketIngestionService;
    }

    public Optional<String> resolve(String instrumentTag) {
        for (Instrument instrument : Instrument.values()) {
            if (instrument.name().equals(instrumentTag)) {
                return Optional.of(instrument.brokerKey());
            }
        }
        return equityBasketIngestionService.findInstrumentKey(instrumentTag);
    }
}

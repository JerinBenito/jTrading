package com.jerin.trading.ingestion;

import com.jerin.trading.broker.BrokerClient;
import com.jerin.trading.broker.FuturesContract;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Backfills the *current* near-month futures contract for an underlying (e.g. NIFTY, BANKNIFTY)
 * under its own instrument tag (e.g. "NIFTY_FUT") — separate from the underlying index's own
 * candles, since futures trade at a premium/discount to the index and must never be mixed into
 * the same series. Unlike the index, a futures contract carries real trading volume, which is
 * what unblocks volume-confirmed pattern analysis (the index itself always reports zero/null
 * volume — see {@link com.jerin.trading.pattern.VolumeConfirmationBacktestService}).
 *
 * A single contract only exists for a few months before expiring and being replaced by the next
 * one — this only backfills whatever history exists for the *currently* near-month contract, not
 * a multi-year continuous series stitched across many expired contracts (a bigger, separate task).
 *
 * {@link #ingestTodayNearMonth} re-resolves the near-month contract every call, so ingestion
 * keeps working unattended across a rollover — but rollover itself isn't smoothed: the old and
 * new contracts' candles land in the same instrument-tagged series back to back, and their
 * prices can differ slightly at the boundary (different time-to-expiry). Fine for volume
 * confirmation (each occurrence is scored independently); would need a proper adjustment if this
 * series were ever used for continuous price-level analysis across a rollover.
 */
@Service
public class FuturesIngestionService {

    private static final int MAX_HOURLY_WINDOW_DAYS = 89;

    private final BrokerClient brokerClient;
    private final IngestionService ingestionService;

    public FuturesIngestionService(BrokerClient brokerClient, IngestionService ingestionService) {
        this.brokerClient = brokerClient;
        this.ingestionService = ingestionService;
    }

    public Optional<FuturesIngestionResult> backfillNearMonth(String underlyingSymbol, String instrumentTag,
                                                                String unit, int interval, LocalDate from, LocalDate to) {
        Optional<FuturesContract> contract = brokerClient.findNearMonthFuture(underlyingSymbol);
        if (contract.isEmpty()) {
            return Optional.empty();
        }
        FuturesContract futuresContract = contract.get();

        int windowDays = "hours".equals(unit) ? MAX_HOURLY_WINDOW_DAYS : Integer.MAX_VALUE;
        int totalSaved = 0;
        LocalDate chunkFrom = from;
        while (!chunkFrom.isAfter(to)) {
            LocalDate chunkTo = chunkFrom.plusDays(windowDays - 1);
            if (chunkTo.isAfter(to)) {
                chunkTo = to;
            }
            totalSaved += ingestionService.ingestCandlesForKey(
                    instrumentTag, futuresContract.instrumentKey(), unit, interval, chunkFrom, chunkTo);
            chunkFrom = chunkTo.plusDays(1);
        }

        return Optional.of(new FuturesIngestionResult(
                instrumentTag,
                futuresContract.tradingSymbol(),
                futuresContract.instrumentKey(),
                futuresContract.expiry().toString(),
                totalSaved));
    }

    /** Today's live/forming candles for whichever contract is near-month right now — what the hourly job calls. */
    public Optional<FuturesIngestionResult> ingestTodayNearMonth(String underlyingSymbol, String instrumentTag,
                                                                    String unit, int interval) {
        Optional<FuturesContract> contract = brokerClient.findNearMonthFuture(underlyingSymbol);
        if (contract.isEmpty()) {
            return Optional.empty();
        }
        FuturesContract futuresContract = contract.get();
        int saved = ingestionService.ingestIntradayCandlesForKey(instrumentTag, futuresContract.instrumentKey(), unit, interval);
        return Optional.of(new FuturesIngestionResult(
                instrumentTag,
                futuresContract.tradingSymbol(),
                futuresContract.instrumentKey(),
                futuresContract.expiry().toString(),
                saved));
    }
}

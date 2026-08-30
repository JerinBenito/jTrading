package com.jerin.trading.ingestion;

import com.jerin.trading.broker.BrokerClient;
import com.jerin.trading.broker.Candle;
import com.jerin.trading.broker.OptionChainEntry;
import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.domain.OptionChainSnapshot;
import com.jerin.trading.repository.OhlcvCandleRepository;
import com.jerin.trading.repository.OptionChainSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final BrokerClient brokerClient;
    private final OhlcvCandleRepository candleRepository;
    private final OptionChainSnapshotRepository optionChainRepository;

    public IngestionService(BrokerClient brokerClient, OhlcvCandleRepository candleRepository,
                             OptionChainSnapshotRepository optionChainRepository) {
        this.brokerClient = brokerClient;
        this.candleRepository = candleRepository;
        this.optionChainRepository = optionChainRepository;
    }

    /** Past, completed trading days only — see {@link com.jerin.trading.broker.BrokerClient#getCandles}. */
    @Transactional
    public int ingestCandles(Instrument instrument, String unit, int interval, LocalDate from, LocalDate to) {
        List<Candle> candles = brokerClient.getCandles(instrument.brokerKey(), unit, interval, from, to);
        return saveNewCandles(instrument.name(), unit, interval, candles);
    }

    /** Today's live/forming candles — what the hourly job needs; {@link #ingestCandles} would return nothing for "today". */
    @Transactional
    public int ingestIntradayCandles(Instrument instrument, String unit, int interval) {
        List<Candle> candles = brokerClient.getIntradayCandles(instrument.brokerKey(), unit, interval);
        return saveNewCandles(instrument.name(), unit, interval, candles);
    }

    /**
     * Same as {@link #ingestCandles} but for an instrument outside the fixed {@link Instrument}
     * enum — e.g. a futures contract, whose broker instrument_key changes month to month and is
     * looked up dynamically rather than hard-coded. `instrumentTag` is the value stored in
     * `ohlcv_candles.instrument` (e.g. "NIFTY_FUT"), independent of the actual broker key used.
     */
    @Transactional
    public int ingestCandlesForKey(String instrumentTag, String instrumentKey, String unit, int interval,
                                    LocalDate from, LocalDate to) {
        List<Candle> candles = brokerClient.getCandles(instrumentKey, unit, interval, from, to);
        return saveNewCandles(instrumentTag, unit, interval, candles);
    }

    /** Same as {@link #ingestIntradayCandles} but for an instrument outside the fixed enum — see {@link #ingestCandlesForKey}. */
    @Transactional
    public int ingestIntradayCandlesForKey(String instrumentTag, String instrumentKey, String unit, int interval) {
        List<Candle> candles = brokerClient.getIntradayCandles(instrumentKey, unit, interval);
        return saveNewCandles(instrumentTag, unit, interval, candles);
    }

    private int saveNewCandles(String instrumentTag, String unit, int interval, List<Candle> candles) {
        String intervalLabel = intervalLabel(unit, interval);
        int saved = 0;
        for (Candle candle : candles) {
            if (candleRepository.existsByInstrumentAndIntervalAndTs(instrumentTag, intervalLabel, candle.ts())) {
                continue;
            }
            candleRepository.save(OhlcvCandle.builder()
                    .instrument(instrumentTag)
                    .interval(intervalLabel)
                    .ts(candle.ts())
                    .open(candle.open())
                    .high(candle.high())
                    .low(candle.low())
                    .close(candle.close())
                    .volume(candle.volume())
                    .build());
            saved++;
        }
        log.info("Ingested {} new {} candles for {}", saved, intervalLabel, instrumentTag);
        return saved;
    }

    @Transactional
    public int ingestOptionChain(Instrument instrument, String expiry) {
        List<OptionChainEntry> entries = brokerClient.getOptionChain(instrument.brokerKey(), expiry);
        OffsetDateTime snapshotTs = OffsetDateTime.now(IST);

        List<OptionChainSnapshot> rows = new ArrayList<>(entries.size() * 2);
        for (OptionChainEntry entry : entries) {
            rows.add(toSnapshot(instrument, entry, entry.call(), "CE", snapshotTs));
            rows.add(toSnapshot(instrument, entry, entry.put(), "PE", snapshotTs));
        }
        optionChainRepository.saveAll(rows);
        log.info("Ingested {} option chain rows for {} ({})", rows.size(), instrument, expiry);
        return rows.size();
    }

    private OptionChainSnapshot toSnapshot(Instrument instrument, OptionChainEntry entry,
                                            OptionChainEntry.OptionLeg leg, String optionType, OffsetDateTime ts) {
        return OptionChainSnapshot.builder()
                .instrument(instrument.name())
                .expiry(entry.expiry())
                .strike(entry.strikePrice())
                .optionType(optionType)
                .ts(ts)
                .oi(leg.oi())
                .changeOi(leg.changeOi())
                .iv(leg.iv())
                .ltp(leg.ltp())
                .volume(leg.volume())
                .build();
    }

    private String intervalLabel(String unit, int interval) {
        String suffix = switch (unit) {
            case "minutes" -> "m";
            case "hours" -> "h";
            case "days" -> "d";
            case "weeks" -> "w";
            case "months" -> "mo";
            default -> unit;
        };
        return interval + suffix;
    }
}

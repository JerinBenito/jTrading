package com.jerin.trading.broker;

import java.time.LocalDate;

/** A tradable futures contract on some underlying — unlike the underlying index itself, this carries real trading volume. */
public record FuturesContract(String instrumentKey, String tradingSymbol, LocalDate expiry) {
}

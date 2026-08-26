export const API_BASE_URL = 'https://jerintradingsignal.duckdns.org';

export const INSTRUMENTS = ['NIFTY', 'BANKNIFTY'] as const;
export type Instrument = (typeof INSTRUMENTS)[number];

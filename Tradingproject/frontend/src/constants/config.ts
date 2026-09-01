export const API_BASE_URL = 'https://jerintradingsignal.duckdns.org';
export const WS_BASE_URL = API_BASE_URL.replace(/^http/, 'ws');

export const INSTRUMENTS = ['NIFTY', 'BANKNIFTY'] as const;
export type Instrument = (typeof INSTRUMENTS)[number];

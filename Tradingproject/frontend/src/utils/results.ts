import type { ResultsCalendarEntry } from '../api/types';

/** Short chip text for a quarterly-results date, relative to today (daysUntil comes from the backend, IST). */
export function resultsChipLabel(entry: ResultsCalendarEntry): string {
  if (entry.daysUntil === 0) return 'RESULTS TODAY';
  if (entry.daysUntil === -1) return 'RESULTS YESTERDAY';
  if (entry.daysUntil === 1) return 'RESULTS TOMORROW';
  return `RESULTS IN ${entry.daysUntil}D`;
}

/** Plain-language explanation for the stock page. The numbers are the measured averages from the
 * 342-event study of these stocks (2024-2026): ~2.4% average absolute move on the reaction day vs
 * ~1.15% on an ordinary day. Dates come from a data vendor and can shift, so they are "expected". */
export function resultsExplanation(entry: ResultsCalendarEntry): string {
  const typical = 'Around results these stocks have historically moved about twice as much as usual (about 2.4% on the reaction day versus 1.2% normally).';
  if (entry.daysUntil === 0) {
    return `Quarterly results expected today. ${typical} The predicted range is widened for this, but expect it to be more often wrong than on a normal day. Most companies announce after the close, so the reaction usually lands next session.`;
  }
  if (entry.daysUntil === -1) {
    return `Quarterly results were due yesterday. Most companies announce after the close, so today is the usual reaction day. ${typical}`;
  }
  return `Quarterly results expected in ${entry.daysUntil} day${entry.daysUntil === 1 ? '' : 's'} (date from a data vendor, may shift). ${typical}`;
}

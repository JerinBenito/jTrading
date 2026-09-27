export interface Theme {
  background: string;
  surface: string;
  surfaceAlt: string;
  border: string;
  textPrimary: string;
  textSecondary: string;
  textMuted: string;
  up: string;
  down: string;
  neutral: string;
  accent: string;
  /** Distinct from `accent` (the deterministic model's color) so AI-related UI is visually
   * identifiable at a glance across the app. */
  ai: string;
}

export const darkTheme: Theme = {
  background: '#0B1220',
  surface: '#131C2E',
  surfaceAlt: '#1B2740',
  border: '#263252',
  textPrimary: '#F2F5FA',
  textSecondary: '#8C99B4',
  textMuted: '#5C6A87',
  up: '#3ECF8E',
  down: '#F2545B',
  neutral: '#F2B84B',
  accent: '#5B8CFF',
  ai: '#B57BFF',
};

/** Not a naive invert of the dark palette — up/down/neutral/accent/ai are all deepened so they
 * keep the same rough contrast ratio against a white surface that the dark palette has against
 * its near-black surface. */
export const lightTheme: Theme = {
  background: '#F7F8FC',
  surface: '#FFFFFF',
  surfaceAlt: '#EEF1F8',
  border: '#E1E5F0',
  textPrimary: '#101828',
  textSecondary: '#475467',
  textMuted: '#94A3B8',
  up: '#12875A',
  down: '#D92D3C',
  neutral: '#B7791F',
  accent: '#3D5FE0',
  ai: '#8B4FE0',
};

import { createContext, useContext, useState, type ReactNode } from 'react';
import { Instrument } from '../constants/config';

interface InstrumentContextValue {
  instrument: Instrument;
  setInstrument: (instrument: Instrument) => void;
}

const InstrumentContext = createContext<InstrumentContextValue | undefined>(undefined);

export function InstrumentProvider({ children }: { children: ReactNode }) {
  const [instrument, setInstrument] = useState<Instrument>('NIFTY');
  return (
    <InstrumentContext.Provider value={{ instrument, setInstrument }}>
      {children}
    </InstrumentContext.Provider>
  );
}

export function useInstrument() {
  const ctx = useContext(InstrumentContext);
  if (!ctx) {
    throw new Error('useInstrument must be used within an InstrumentProvider');
  }
  return ctx;
}

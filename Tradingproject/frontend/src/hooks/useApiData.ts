import { useCallback, useEffect, useState } from 'react';

interface ApiDataState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
  refresh: () => void;
}

/** Fetches on mount and whenever `deps` changes (e.g. the selected instrument); `refresh` re-runs it on demand (pull-to-refresh). */
export function useApiData<T>(fetcher: () => Promise<T>, deps: unknown[]): ApiDataState<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);

  const load = useCallback(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetcher()
      .then((result) => {
        if (!cancelled) {
          setData(result);
        }
      })
      .catch((err: Error) => {
        if (!cancelled) {
          setError(err.message);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, refreshToken]);

  useEffect(() => load(), [load]);

  const refresh = useCallback(() => setRefreshToken((t) => t + 1), []);

  return { data, loading, error, refresh };
}

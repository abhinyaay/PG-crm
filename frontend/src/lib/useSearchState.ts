import { useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';

/**
 * List filters kept in the URL (shareable, back-button friendly). Setting any filter other than `page`
 * resets the page to 0.
 */
export function useSearchState() {
  const [params, setParams] = useSearchParams();
  const get = useCallback((key: string) => params.get(key) ?? '', [params]);
  const getNumber = useCallback((key: string) => {
    const v = params.get(key);
    return v && !Number.isNaN(Number(v)) ? Number(v) : undefined;
  }, [params]);
  const set = useCallback(
    (patch: Record<string, string | number | boolean | undefined | null>) => {
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          for (const [k, v] of Object.entries(patch)) {
            if (v === undefined || v === null || v === '' || v === false) next.delete(k);
            else next.set(k, String(v));
          }
          if (!('page' in patch)) next.delete('page');
          return next;
        },
        { replace: true },
      );
    },
    [setParams],
  );
  const page = getNumber('page') ?? 0;
  return { get, getNumber, set, page, params };
}

import { useSyncExternalStore } from 'react';

/**
 * Chart colors (validated reference palette: categorical slots 1-2, blue sequential ramp),
 * stepped separately for light and dark surfaces.
 */
const LIGHT = {
  series1: '#2a78d6',
  series2: '#eb6834',
  grid: '#e5e7eb',
  axis: '#64748b',
  text: '#0f172a',
  surface: '#ffffff',
  ramp: ['#86b6ef', '#5598e7', '#2a78d6', '#1c5cab', '#104281'],
};
const DARK = {
  series1: '#3987e5',
  series2: '#d95926',
  grid: '#1e293b',
  axis: '#94a3b8',
  text: '#f1f5f9',
  surface: '#0f172a',
  ramp: ['#184f95', '#1c5cab', '#2a78d6', '#5598e7', '#9ec5f4'],
};
export type ChartTheme = typeof LIGHT;

const query = '(prefers-color-scheme: dark)';
function subscribe(cb: () => void) {
  if (typeof window === 'undefined' || !window.matchMedia) return () => undefined;
  const mql = window.matchMedia(query);
  mql.addEventListener('change', cb);
  return () => mql.removeEventListener('change', cb);
}
const isDark = () => (typeof window !== 'undefined' && window.matchMedia ? window.matchMedia(query).matches : false);

export function useChartTheme(): ChartTheme {
  const dark = useSyncExternalStore(subscribe, isDark, () => false);
  return dark ? DARK : LIGHT;
}

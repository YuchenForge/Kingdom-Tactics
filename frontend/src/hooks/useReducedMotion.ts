import { useSyncExternalStore } from 'react'
const query = '(prefers-reduced-motion: reduce)'
function subscribe(update: () => void) {
  if (typeof window.matchMedia !== 'function') return () => {}
  const media = window.matchMedia(query)
  media.addEventListener('change', update)
  return () => media.removeEventListener('change', update)
}
const snapshot = () => typeof window.matchMedia === 'function' && window.matchMedia(query).matches
export function useReducedMotion() { return useSyncExternalStore(subscribe, snapshot, () => true) }

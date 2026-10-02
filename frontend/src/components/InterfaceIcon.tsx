// Inline markup from the existing, repository-owned interface SVG assets.
const icons = {
  'gold': '<g stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="m12 6 4 6-4 6-4-6Z"/></g>',
  'timer': '<g stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="13" r="8"/><path d="M9 2h6m-3 3V2m0 6v5l3 2m4-9 2-2"/></g>',
  'lock': '<g stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 5v2"/></g>',
  'sell': '<g stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="m3 4 9-1 9 9-9 9-9-9Z"/><circle cx="8" cy="8" r="1"/><path d="m12 11 4 4m-5-1 4-4"/></g>',
  'round': '<g stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M6 3h12M6 21h12M7 3v4l10 10v4M17 3v4L7 17v4"/></g>',
  'keep-hp': '<g stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 20V5h4v4h3V5h3v4h3V5h3v15ZM10 20v-6h4v6"/></g>',
  'refresh': '<g stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M20 9a8 8 0 0 0-14-3L3 9m0-6v6h6M4 15a8 8 0 0 0 14 3l3-3m0 6v-6h-6"/></g>',
  'unit-cap': '<g stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="9" cy="7" r="3"/><path d="M3 20v-3a6 6 0 0 1 12 0v3M16 4a3 3 0 0 1 0 6m2 4a5 5 0 0 1 3 4v2"/></g>',
} as const
export default function InterfaceIcon({ name }: { name: keyof typeof icons }) {
  return <svg className="interface-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true" focusable="false" dangerouslySetInnerHTML={{ __html: icons[name] }} />
}

import { useLayoutEffect, useRef } from 'react'
import type { ReactNode, RefObject } from 'react'
import { createPortal } from 'react-dom'

function popupPosition(anchor: { left: number; top: number; bottom: number }, width: number, height: number, viewportWidth: number, viewportHeight: number) {
  const left = Math.max(12, Math.min(anchor.left, viewportWidth - width - 12))
  const below = anchor.bottom + 4
  const top = Math.max(12, Math.min(below + height <= viewportHeight - 12 ? below : anchor.top - height - 4, viewportHeight - height - 12))
  return { left, top }
}

export default function UnitPopup({ anchor, label, children, onEnter, onLeave, onClose }: {
  anchor: RefObject<HTMLDivElement | null>; label: string; children: ReactNode;
  onEnter: () => void; onLeave: () => void; onClose: () => void;
}) {
  const popup = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    function position() {
      if (!anchor.current || !popup.current) return
      const viewport = window.visualViewport
      const width = viewport?.width ?? window.innerWidth
      const height = viewport?.height ?? window.innerHeight
      const offsetX = viewport?.offsetLeft ?? 0
      const offsetY = viewport?.offsetTop ?? 0
      const rect = anchor.current.getBoundingClientRect()
      popup.current.style.maxHeight = `${Math.max(80, height - 24)}px`
      popup.current.style.maxWidth = `${Math.max(80, width - 24)}px`
      const bounds = popup.current.getBoundingClientRect()
      const point = popupPosition({ left: rect.left - offsetX, top: rect.top - offsetY, bottom: rect.bottom - offsetY }, bounds.width, bounds.height, width, height)
      popup.current.style.left = `${point.left + offsetX}px`
      popup.current.style.top = `${point.top + offsetY}px`
    }
    position()
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(position)
    if (popup.current) observer?.observe(popup.current)
    window.addEventListener('resize', position)
    window.addEventListener('scroll', position, true)
    window.visualViewport?.addEventListener('resize', position)
    window.visualViewport?.addEventListener('scroll', position)
    return () => {
      observer?.disconnect()
      window.removeEventListener('resize', position)
      window.removeEventListener('scroll', position, true)
      window.visualViewport?.removeEventListener('resize', position)
      window.visualViewport?.removeEventListener('scroll', position)
    }
  }, [anchor])
  return createPortal(<div ref={popup} className="unit-popup viewport-popup" role="dialog" aria-label={label}
    onPointerEnter={onEnter} onPointerLeave={onLeave} onKeyDown={(event) => { if (event.key === 'Escape') onClose() }}>{children}</div>, document.body)
}

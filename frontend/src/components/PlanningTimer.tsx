import InterfaceIcon from './InterfaceIcon'
import { useEffect, useState } from 'react'

export default function PlanningTimer({ deadline }: { deadline: string }) {
  const [now, setNow] = useState(Date.now)
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 250)
    return () => window.clearInterval(timer)
  }, [])
  const seconds = Math.max(0, Math.min(45, Math.ceil((Date.parse(deadline) - now) / 1000)))
  const text = Number.isFinite(seconds) ? `00:${String(seconds).padStart(2, '0')}` : '—'
  return <span className="planning-timer" role="timer" aria-label="Planning time remaining" title={deadline}>
    <InterfaceIcon name="timer" />{text}{seconds === 0 && <small> · Waiting for server…</small>}
  </span>
}

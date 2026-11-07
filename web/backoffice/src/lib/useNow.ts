import { useEffect, useState } from 'react'

/** The clock, re-read every `ms`: drives countdowns, "il y a" labels and the greeting. */
export function useNow(ms: number) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), ms)
    return () => clearInterval(id)
  }, [ms])
  return now
}

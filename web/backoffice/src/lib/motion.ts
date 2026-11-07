// DESIGN §6: one-time entrance (fade + rise, 420 ms max), 60 ms stagger capped after 5 items; off under reduced motion.
export const RISE = 'animate-in fade-in-0 slide-in-from-bottom-2 duration-[420ms] ease-out fill-mode-backwards motion-reduce:animate-none'
export const stagger = (i: number) => ({ animationDelay: `${Math.min(i, 5) * 60}ms` })

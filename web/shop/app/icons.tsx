// Stroke icons (DESIGN.md: 1.8 px stroke, round caps, 20–22 px). Decorative: the text next to each carries the meaning.
// Directional ones (arrows) mirror in RTL.

type P = { className?: string }
const svg = (className: string | undefined, children: React.ReactNode) => (
  <svg
    aria-hidden="true"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={1.8}
    strokeLinecap="round"
    strokeLinejoin="round"
    className={`shrink-0 ${className ?? 'size-5'}`}
  >
    {children}
  </svg>
)

export const CalendarIcon = ({ className }: P) =>
  svg(
    className,
    <>
      <rect x="3.5" y="5" width="17" height="15" rx="3" />
      <path d="M3.5 10h17M8 3v4M16 3v4" />
    </>,
  )
export const PinIcon = ({ className }: P) =>
  svg(
    className,
    <>
      <path d="M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21z" />
      <circle cx="12" cy="9.5" r="2.5" />
    </>,
  )
export const ArrowIcon = ({ className }: P) => svg(`rtl:-scale-x-100 ${className ?? 'size-5'}`, <path d="M5 12h14M13 6l6 6-6 6" />)
export const BackIcon = ({ className }: P) => svg(`rtl:-scale-x-100 ${className ?? 'size-5'}`, <path d="M19 12H5M11 6l-6 6 6 6" />)
export const CheckIcon = ({ className }: P) => svg(className, <path d="M5 12.5l4.5 4.5L19 7.5" />)
export const CardIcon = ({ className }: P) =>
  svg(
    className,
    <>
      <rect x="3" y="5.5" width="18" height="13" rx="2.5" />
      <path d="M3 10h18M7 15h3" />
    </>,
  )
export const TicketIcon = ({ className }: P) =>
  svg(
    className,
    <>
      <path d="M4 7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-2a2 2 0 0 0 0-4z" />
      <path d="M14 5v12" strokeDasharray="2 2.5" />
    </>,
  )
export const QrIcon = ({ className }: P) =>
  svg(
    className,
    <>
      <rect x="4" y="4" width="6" height="6" rx="1" />
      <rect x="14" y="4" width="6" height="6" rx="1" />
      <rect x="4" y="14" width="6" height="6" rx="1" />
      <path d="M14 14h2v2h-2zM18 18h2v2h-2zM14 18v2M20 14v2" />
    </>,
  )
export const PhoneIcon = ({ className }: P) =>
  svg(
    className,
    <>
      <rect x="6.5" y="2.5" width="11" height="19" rx="2.5" />
      <path d="M11 18.5h2" />
    </>,
  )
export const DownloadIcon = ({ className }: P) => svg(className, <path d="M12 4v11M7 10.5l5 5 5-5M5 20h14" />)
export const PersonIcon = ({ className }: P) =>
  svg(
    className,
    <>
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6" />
    </>,
  )
export const LogoutIcon = ({ className }: P) => svg(`rtl:-scale-x-100 ${className ?? 'size-5'}`, <path d="M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3M16 16l4-4-4-4M20 12H10" />)
export const ClockIcon = ({ className }: P) =>
  svg(
    className,
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </>,
  )
export const WalletIcon = ({ className }: P) =>
  svg(
    className,
    <>
      <path d="M4 7.5A2.5 2.5 0 0 1 6.5 5H18v3" />
      <rect x="4" y="8" width="16" height="11" rx="2.5" />
      <path d="M16 13.5h1.5" />
    </>,
  )
export const CashIcon = ({ className }: P) =>
  svg(
    className,
    <>
      <rect x="2.5" y="6" width="19" height="12" rx="2.5" />
      <circle cx="12" cy="12" r="2.5" />
      <path d="M6 9.5v5M18 9.5v5" />
    </>,
  )
export const CopyIcon = ({ className }: P) =>
  svg(
    className,
    <>
      <rect x="8.5" y="8.5" width="11" height="11" rx="2.5" />
      <path d="M15.5 8.5V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v7.5a2 2 0 0 0 2 2h2.5" />
    </>,
  )

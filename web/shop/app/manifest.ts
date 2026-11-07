import type { MetadataRoute } from 'next'
import tokens from '@/lib/tokens'
import icon1024 from '../../../design/logo/app-icon-1024.png'
import icon512 from '../../../design/logo/app-icon-512.png'

/** Web app manifest: the Jomhor app icon from design/logo (BRAND.md §Where the logo appears), ink chrome. */
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: 'Jomhor',
    short_name: 'Jomhor',
    start_url: '/',
    display: 'standalone',
    background_color: tokens.color.ground,
    theme_color: tokens.color.ground,
    icons: [
      { src: icon512.src, sizes: '512x512', type: 'image/png' },
      { src: icon1024.src, sizes: '1024x1024', type: 'image/png' },
    ],
  }
}

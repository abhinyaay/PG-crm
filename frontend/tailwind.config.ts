import type { Config } from 'tailwindcss';

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  // light + dark mode follow the OS setting (prefers-color-scheme)
  darkMode: 'media',
  theme: {
    extend: {
      colors: {
        brand: {
          50: '#eef6ff',
          100: '#d9eaff',
          200: '#bcdaff',
          300: '#8ec2ff',
          400: '#599fff',
          500: '#337bfc',
          600: '#1d5cf1',
          700: '#1547de',
          800: '#183bb4',
          900: '#1a378e',
          950: '#152356',
        },
      },
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'sans-serif'],
      },
      minHeight: { touch: '44px' },
      minWidth: { touch: '44px' },
      spacing: { 'safe-bottom': 'env(safe-area-inset-bottom)' },
    },
  },
  plugins: [],
} satisfies Config;

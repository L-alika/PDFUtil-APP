/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{vue,js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"Inter"', '"Noto Sans SC"', '-apple-system', 'BlinkMacSystemFont', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'monospace'],
      },
      colors: {
        canvas: {
          bg: '#F5F3EF',
          sidebar: '#FAF9F6',
          surface: '#FFFFFF',
          subtle: '#EFECE6',
          hover: '#F0EDE7',
          border: '#E6E2DA',
          borderStrong: '#D5CFC4'
        },
        ink: {
          primary: '#1C1917',
          secondary: '#57534E',
          muted: '#8A847C',
          faint: '#A8A29E'
        },
        brand: {
          50: '#EEF2FF',
          100: '#E0E7FF',
          500: '#3B5BDB',
          600: '#2F4BC2',
          700: '#243B9E'
        },
        amberWarm: {
          50: '#FFFBEB',
          100: '#FEF3C7',
          500: '#D97706',
          600: '#B45309'
        },
        sage: {
          50: '#ECFDF5',
          100: '#D1FAE5',
          600: '#059669',
          700: '#047857'
        },
        rose: {
          50: '#FFF1F2',
          100: '#FFE4E6',
          200: '#FECDD3',
          300: '#FDA4AF',
          400: '#FB7185',
          500: '#F43F5E',
          600: '#E11D48',
          700: '#BE123C'
        }
      },
      boxShadow: {
        'craft-sm': '0 1px 2px rgba(28, 25, 23, 0.04), 0 0 0 1px rgba(28, 25, 23, 0.05)',
        'craft-md': '0 2px 6px -1px rgba(28, 25, 23, 0.05), 0 8px 20px -4px rgba(28, 25, 23, 0.05), 0 0 0 1px rgba(28, 25, 23, 0.06)',
        'craft-lg': '0 4px 12px -2px rgba(28, 25, 23, 0.06), 0 16px 36px -6px rgba(28, 25, 23, 0.07), 0 0 0 1px rgba(28, 25, 23, 0.06)',
        'craft-float': '0 12px 32px -4px rgba(28, 25, 23, 0.12), 0 2px 6px rgba(28, 25, 23, 0.06), 0 0 0 1px rgba(28, 25, 23, 0.08)',
        'paper': '0 2px 4px rgba(28, 25, 23, 0.04), 0 12px 28px -4px rgba(28, 25, 23, 0.08), 0 0 0 1px rgba(28, 25, 23, 0.07)'
      }
    }
  },
  plugins: [],
}

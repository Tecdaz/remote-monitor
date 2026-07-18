/** @type {import('tailwindcss').Config} */
export default {
  content: ['./app/**/*.{ts,tsx}', './components/**/*.{ts,tsx}', './hooks/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        clinical: {
          surface: '#F6F8FB',
          panel: '#FFFFFF',
          subtle: '#EEF2F7',
          border: '#DDE3EC',
          borderStrong: '#C5CDD9',
          ink: '#0F172A',
          inkMuted: '#475569',
          inkFaint: '#64748B',
          accent: '#0D9488',
          accentStrong: '#0F766E',
          accentSoft: '#CCFBF1',
        },
        status: {
          live: '#0D9488',
          ok: '#15803D',
          okBg: '#DCFCE7',
          warn: '#B45309',
          warnBg: '#FEF3C7',
          danger: '#B91C1C',
          dangerBg: '#FEE2E2',
        },
        chart: {
          line: '#0D9488',
          lineLatest: '#0F766E',
          axis: '#64748B',
          grid: '#E2E8F0',
          identity: '#94A3B8',
          vlf: '#A78BFA',
          lf: '#FBBF24',
          hf: '#14B8A6',
        },
      },
      fontFamily: {
        sans: [
          'Inter',
          'system-ui',
          '-apple-system',
          'Segoe UI',
          'Roboto',
          'sans-serif',
        ],
        mono: [
          'ui-monospace',
          'SFMono-Regular',
          'Menlo',
          'Monaco',
          'Consolas',
          'monospace',
        ],
      },
      boxShadow: {
        card: '0 1px 2px rgba(15, 23, 42, 0.04), 0 1px 3px rgba(15, 23, 42, 0.06)',
        cardHover: '0 2px 4px rgba(15, 23, 42, 0.05), 0 4px 12px rgba(15, 23, 42, 0.06)',
      },
      borderRadius: {
        card: '0.875rem',
      },
      keyframes: {
        'pulse-live': {
          '0%, 100%': {
            opacity: '1',
            boxShadow: '0 0 0 0 rgba(13, 148, 136, 0.55)',
          },
          '50%': {
            opacity: '0.65',
            boxShadow: '0 0 0 10px rgba(13, 148, 136, 0)',
          },
        },
        'pulse-warn': {
          '0%, 100%': {
            opacity: '1',
            boxShadow: '0 0 0 0 rgba(180, 83, 9, 0.55)',
          },
          '50%': {
            opacity: '0.65',
            boxShadow: '0 0 0 10px rgba(180, 83, 9, 0)',
          },
        },
        'pulse-danger': {
          '0%, 100%': {
            opacity: '1',
            boxShadow: '0 0 0 0 rgba(185, 28, 28, 0.55)',
          },
          '50%': {
            opacity: '0.6',
            boxShadow: '0 0 0 10px rgba(185, 28, 28, 0)',
          },
        },
        'fade-in': {
          from: { opacity: '0', transform: 'translateY(2px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
      },
      animation: {
        'pulse-live': 'pulse-live 1.8s ease-out infinite',
        'pulse-warn': 'pulse-warn 1.8s ease-out infinite',
        'pulse-danger': 'pulse-danger 1.8s ease-out infinite',
        'fade-in': 'fade-in 220ms ease-out both',
      },
    },
  },
  plugins: [],
}
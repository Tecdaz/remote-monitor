/** @type {import('tailwindcss').Config} */
export default {
  content: ['./app/**/*.{ts,tsx}', './components/**/*.{ts,tsx}', './hooks/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        medical: {
          bg: '#0B0F19',
          panel: '#111827',
          teal: '#14b8a6',
        },
      },
    },
  },
  plugins: [],
}

/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './index.html',
    './src/**/*.{js,jsx,ts,tsx}',
  ],
  theme: {
    extend: {
      colors: {
        // MTG-themed dark palette
        mtg: {
          bg: '#0a0c0e',
          surface: '#111418',
          panel: '#1a1f26',
          border: '#2a3040',
          gold: '#c9a84c',
          'gold-light': '#e8c96a',
          'gold-dark': '#8a6a1a',
          green: '#1a3a1a',
          'green-light': '#2d5a2d',
          text: '#d4c9a8',
          'text-muted': '#7a8090',
          // Mana colors
          white: '#f8f0d8',
          blue: '#1a4a8a',
          black: '#2a1a3a',
          red: '#8a2020',
          'green-mana': '#1a6a2a',
          colorless: '#5a6070',
        },
        // Phase colors
        phase: {
          untap: '#4a9a5a',
          upkeep: '#9a8a4a',
          draw: '#4a7a9a',
          main: '#7a9a4a',
          combat: '#9a4a4a',
          end: '#6a6a8a',
        },
      },
      boxShadow: {
        'mtg-card': '0 4px 16px rgba(0,0,0,0.7), 0 0 2px rgba(201,168,76,0.2)',
        'mtg-glow': '0 0 12px rgba(201,168,76,0.5)',
        'mtg-highlight': '0 0 8px 2px rgba(100,200,100,0.6)',
        'mtg-target': '0 0 8px 2px rgba(200,100,100,0.7)',
      },
      fontFamily: {
        mtg: ['"Palatino Linotype"', 'Palatino', 'Georgia', 'serif'],
        ui: ['"Inter"', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
}

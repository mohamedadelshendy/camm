/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        dark: {
          950: "#070B14",
          900: "#0D1424",
          800: "#131E36",
          700: "#1E293B",
        },
        cyan: {
          400: "#22D3EE",
          500: "#06B6D4",
        },
        emerald: {
          400: "#34D399",
          500: "#10B981",
        }
      }
    },
  },
  plugins: [],
}

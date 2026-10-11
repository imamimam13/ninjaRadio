import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./src/pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      colors: {
        ninja: {
          dark: "#0a0c10",
          card: "#121721",
          border: "#1e293b",
          cyan: "#00f0ff",
          green: "#10b981",
          amber: "#f59e0b",
          red: "#ef4444",
          purple: "#a855f7"
        }
      },
      screens: {
        'poc': '240px',   // 2.4" Network Radio screen
        'poc-wide': '320px', // 2.8" Network Radio screen
        'xs': '420px',
        'sm': '640px',
        'md': '768px',
        'lg': '1024px',
        'xl': '1280px',
      }
    },
  },
  plugins: [],
};
export default config;

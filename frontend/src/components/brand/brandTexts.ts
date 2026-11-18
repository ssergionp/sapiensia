export const BRAND_TEXTS = {
  homeLink: "SapiensIA — página inicial",
  tagline: "Conhecimento pronto para usar.",
  copyright: (year: number) => `© ${year} SapiensIA`,
} as const;

// Solid ring with a gap in the action color, for the site chrome (header and footer sit on bg-card)
export const CHROME_FOCUS_RING =
  "outline-none focus-visible:border-transparent focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-card";

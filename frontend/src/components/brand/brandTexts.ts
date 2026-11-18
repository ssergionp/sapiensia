import { cn } from "cn";

export const BRAND_TEXTS = {
  homeLink: "SapiensIA — página inicial",
  tagline: "Conhecimento pronto para usar.",
  copyright: (year: number) => `© ${year} SapiensIA`,
} as const;

// The hero title sets the last two words of the tagline apart (italic, action color); a tagline of
// fewer than three words is shown whole. Returns [start, highlight].
export function splitTagline(tagline: string): [string, string] {
  const words = tagline.trim().split(/\s+/);
  if (words.length < 3) return [words.join(" "), ""];
  return [`${words.slice(0, -2).join(" ")} `, words.slice(-2).join(" ")];
}

// Solid ring with a gap in the action color, for the site chrome (header and footer sit on bg-card)
export const CHROME_FOCUS_RING =
  "outline-none focus-visible:border-transparent focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-card";

// Navigation link of the site header and the admin panel bar. The current page gets aria-current from
// NavLink and a decorative Terracota bar (DESIGN.md: Terracota only as decoration in the light theme).
export const NAV_LINK = cn(
  "relative inline-flex min-h-11 items-center rounded-md px-3 text-sm font-semibold hover:text-primary",
  "aria-[current=page]:after:absolute aria-[current=page]:after:inset-x-3 aria-[current=page]:after:bottom-1.5",
  "aria-[current=page]:after:h-0.5 aria-[current=page]:after:rounded-full aria-[current=page]:after:bg-brand-decor",
  CHROME_FOCUS_RING,
);

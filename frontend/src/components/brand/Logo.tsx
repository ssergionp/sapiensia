import { cn } from "cn";

// Symbol: open book in a rounded square with a highlight dot (DESIGN.md, "Marca").
// Colors come from the theme tokens, so the same mark works in light and dark.
export function LogoMark({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 32 32"
      aria-hidden="true"
      focusable="false"
      className={cn("size-8 shrink-0", className)}
    >
      <rect width="32" height="32" rx="8" className="fill-primary" />
      <g
        fill="none"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className="stroke-primary-foreground"
      >
        <path d="M16 11.5c-2.6-1.7-5.6-2.2-8.5-1.9v13c2.9-.3 5.9.2 8.5 1.9 2.6-1.7 5.6-2.2 8.5-1.9v-13c-2.9-.3-5.9.2-8.5 1.9Z" />
        <path d="M16 11.5v12.9" />
      </g>
      <circle cx="16" cy="6.25" r="1.9" className="fill-brand-mark" />
    </svg>
  );
}

// "sm" keeps the footer visibly quieter than the header
export default function Logo({ size = "md" }: { size?: "md" | "sm" }) {
  const small = size === "sm";
  return (
    <span
      className={cn("inline-flex items-center", small ? "gap-2" : "gap-2.5")}
    >
      <LogoMark className={small ? "size-6" : undefined} />
      <span
        className={cn(
          "font-heading font-semibold leading-none tracking-[-0.025em]",
          small ? "text-base" : "text-xl",
        )}
      >
        Sapiens<span className="italic text-primary">IA</span>
      </span>
    </span>
  );
}

import { cn } from "cn";
import type { ReactNode } from "react";

// Color plus the status words (never color alone); every pair is checked in theme-tokens.test.ts or uses
// the text tokens already checked there.
const TONES = {
  success: "bg-success text-success-foreground",
  pending: "bg-brand-decor/20 text-foreground",
  neutral: "bg-muted text-foreground",
  muted: "bg-muted text-muted-foreground",
  outline: "border text-muted-foreground",
} as const;

export type StatusTone = keyof typeof TONES;

type Props = { status: string; tone: StatusTone; children: ReactNode };

export default function StatusBadge({ status, tone, children }: Props) {
  return (
    <span
      data-status={status}
      className={cn(
        "inline-block shrink-0 rounded-full px-3 py-1 text-xs font-semibold whitespace-nowrap",
        TONES[tone],
      )}
    >
      {children}
    </span>
  );
}

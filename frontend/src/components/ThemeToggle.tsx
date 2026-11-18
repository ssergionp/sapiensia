import { Moon, Sun } from "lucide-react";
import { CHROME_FOCUS_RING } from "@/components/brand/brandTexts";
import { Button } from "@/components/ui/button";
import { useTheme } from "@/lib/theme";

const LABELS = {
  light: "Usar tema escuro",
  dark: "Usar tema claro",
} as const;

export default function ThemeToggle() {
  const { theme, toggle } = useTheme();
  const label = LABELS[theme];

  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-lg"
      // cn merges like tailwind-merge: size-11 (44px touch target) and the solid chrome ring replace the
      // icon-lg size and the shared Button halo, which is too faint on Papiro
      className={`size-11 ${CHROME_FOCUS_RING}`}
      aria-label={label}
      title={label}
      onClick={toggle}
    >
      {theme === "dark" ? (
        <Sun aria-hidden="true" className="size-5" />
      ) : (
        <Moon aria-hidden="true" className="size-5" />
      )}
    </Button>
  );
}

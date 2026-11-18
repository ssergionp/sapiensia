import { Moon, Sun } from "lucide-react";
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
      // 44px touch target and a solid focus ring (the shared Button halo is too faint on Papiro)
      className="size-11 focus-visible:border-transparent focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-card"
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

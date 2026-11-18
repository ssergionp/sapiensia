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

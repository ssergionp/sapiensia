import { Link } from "react-router";
import { BRAND_TEXTS } from "@/components/brand/brandTexts";
import Logo from "@/components/brand/Logo";
import ThemeToggle from "@/components/ThemeToggle";

// #186 stage 1: brand and theme only; the navigation links join in stage 2 (plan D2)
export default function SiteHeader() {
  return (
    <header className="border-b bg-card">
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between gap-4 px-4 sm:px-6">
        <Link
          to="/"
          aria-label={BRAND_TEXTS.homeLink}
          className="-mx-1 rounded-md px-1 py-1 outline-none focus-visible:ring-3 focus-visible:ring-ring"
        >
          <Logo />
        </Link>
        <ThemeToggle />
      </div>
    </header>
  );
}

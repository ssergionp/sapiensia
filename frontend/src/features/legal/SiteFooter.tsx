import { Link } from "react-router";
import { BRAND_TEXTS } from "@/components/brand/brandTexts";
import Logo from "@/components/brand/Logo";
import { LEGAL_DOCUMENTS, LEGAL_TEXTS, SUPPORT_EMAIL } from "./legalTexts";

const LINK =
  "rounded-sm underline-offset-4 outline-none hover:text-primary hover:underline focus-visible:underline focus-visible:ring-3 focus-visible:ring-ring";

export default function SiteFooter() {
  return (
    // biome-ignore lint/a11y/useAriaPropsSupportedByRole: a top-level <footer> is the contentinfo landmark and accepts a name
    <footer
      aria-label={LEGAL_TEXTS.footerLabel}
      className="mt-16 border-t bg-card"
    >
      <div className="mx-auto flex max-w-6xl flex-col gap-8 px-4 py-10 sm:px-6 md:flex-row md:justify-between">
        <div className="space-y-3">
          <Logo />
          <p className="text-sm text-muted-foreground">{BRAND_TEXTS.tagline}</p>
        </div>
        <div className="space-y-4 md:text-right">
          <nav className="flex flex-wrap gap-x-6 gap-y-3 text-sm font-medium md:justify-end">
            {LEGAL_DOCUMENTS.map((doc) => (
              <Link key={doc.path} to={doc.path} className={LINK}>
                {doc.title}
              </Link>
            ))}
            <a
              href={`mailto:${SUPPORT_EMAIL}`}
              className={`${LINK} [overflow-wrap:anywhere]`}
            >
              {LEGAL_TEXTS.support}
            </a>
          </nav>
          <p className="text-xs text-muted-foreground">
            {BRAND_TEXTS.copyright}
          </p>
        </div>
      </div>
    </footer>
  );
}

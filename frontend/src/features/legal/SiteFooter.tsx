import { Link } from "react-router";
import { LEGAL_DOCUMENTS, LEGAL_TEXTS, SUPPORT_EMAIL } from "./legalTexts";

export default function SiteFooter() {
  return (
    <footer className="mt-8 border-t p-8">
      <nav
        aria-label={LEGAL_TEXTS.footerLabel}
        className="flex flex-wrap gap-4"
      >
        {LEGAL_DOCUMENTS.map((doc) => (
          <Link key={doc.path} to={doc.path} className="underline">
            {doc.title}
          </Link>
        ))}
        <a href={`mailto:${SUPPORT_EMAIL}`} className="underline">
          {LEGAL_TEXTS.support}
        </a>
      </nav>
    </footer>
  );
}

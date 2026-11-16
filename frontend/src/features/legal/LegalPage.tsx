import { useDocumentTitle } from "@/lib/useDocumentTitle";
import {
  DRAFT_NOTICE,
  DRAFT_VERSION,
  LEGAL_TEXTS,
  type LegalDocument,
} from "./legalTexts";

export default function LegalPage({ document }: { document: LegalDocument }) {
  useDocumentTitle(LEGAL_TEXTS.documentTitle(document.title));

  return (
    <main className="mx-auto max-w-3xl p-8">
      <h1 className="text-2xl font-semibold">{document.title}</h1>
      <p
        role="note"
        className="mt-4 rounded border border-current p-3 font-medium"
      >
        {DRAFT_NOTICE}
      </p>
      <p className="mt-2 text-sm">{DRAFT_VERSION}</p>
      {document.sections.map((section) => (
        <section key={section.heading} className="mt-6">
          <h2 className="text-lg font-semibold">{section.heading}</h2>
          {section.paragraphs?.map((paragraph) => (
            <p key={paragraph} className="mt-2">
              {paragraph}
            </p>
          ))}
          {section.items && (
            <ul className="mt-2 list-disc pl-6">
              {section.items.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          )}
        </section>
      ))}
    </main>
  );
}

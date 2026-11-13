import { useRef } from "react";
import { Link, useSearchParams } from "react-router";
import { Button } from "@/components/ui/button";
import { CATEGORY_LABELS } from "@/features/products/messages";
import { formatCents } from "@/features/products/schemas";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import CoverImage from "./CoverImage";
import { useCatalog } from "./hooks";
import { CATALOG_TEXTS as T } from "./messages";
import { coverUrl } from "./schemas";

// ?pagina= is 1-based; anything that is not 1 to 9 digits with a value of at least 1 means the first page.
function pageFromParam(raw: string | null): number {
  return raw !== null && /^\d{1,9}$/.test(raw) && Number(raw) >= 1
    ? Number(raw) - 1
    : 0;
}

export default function CatalogPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const page = pageFromParam(searchParams.get("pagina"));
  const catalog = useCatalog(page);
  const heading = useRef<HTMLHeadingElement>(null);

  useDocumentTitle(T.listDocumentTitle);

  function goTo(target: number) {
    setSearchParams(target === 0 ? {} : { pagina: String(target + 1) });
    window.scrollTo(0, 0);
    heading.current?.focus();
  }

  let content: React.ReactNode;
  if (catalog.isPending) {
    content = (
      <p role="status" className="mt-4">
        {T.loading}
      </p>
    );
  } else if (catalog.isError) {
    content = (
      <div className="mt-4">
        <p role="alert" className="text-destructive">
          {T.listError}
        </p>
        <Button
          variant="outline"
          className="mt-2"
          onClick={() => catalog.refetch()}
        >
          {T.retry}
        </Button>
      </div>
    );
  } else if (catalog.data.items.length === 0) {
    content =
      page === 0 ? (
        <p className="mt-4">{T.empty}</p>
      ) : (
        <p className="mt-4">
          {T.pastEnd}{" "}
          <Link to="/catalogo" className="underline">
            {T.goToFirstPage}
          </Link>
        </p>
      );
  } else {
    const { items, totalPages } = catalog.data;
    content = (
      <>
        <ul className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
          {items.map((item) => (
            <li key={item.id}>
              <article className="rounded-lg border p-4">
                <CoverImage
                  src={coverUrl(item.id, item.coverVersion)}
                  alt={`Capa de ${item.title}`}
                  className="mb-3 max-w-40"
                />
                <h2 className="text-lg font-semibold [overflow-wrap:anywhere]">
                  <Link to={`/catalogo/${item.id}`} className="underline">
                    {item.title}
                  </Link>
                </h2>
                <p className="mt-1 text-sm">
                  <span className="sr-only">{T.srCategory} </span>
                  {CATEGORY_LABELS[item.category]}{" "}
                  <span aria-hidden="true">·</span>{" "}
                  <span className="sr-only">{T.srPrice} </span>
                  {formatCents(item.priceCents)}
                </p>
                <p className="mt-2 [overflow-wrap:anywhere]">{item.summary}</p>
              </article>
            </li>
          ))}
        </ul>
        {totalPages > 1 && (
          <nav
            aria-label={T.paginationLabel}
            className="mt-4 flex items-center gap-4"
          >
            <Button
              variant="outline"
              disabled={page === 0}
              onClick={() => goTo(page - 1)}
            >
              {T.previous}
            </Button>
            <span>{T.pageOf(page + 1, totalPages)}</span>
            <Button
              variant="outline"
              disabled={page + 1 >= totalPages}
              onClick={() => goTo(page + 1)}
            >
              {T.next}
            </Button>
          </nav>
        )}
      </>
    );
  }

  return (
    <main className="p-8">
      <h1 ref={heading} tabIndex={-1} className="text-2xl font-bold">
        {T.listHeading}
      </h1>
      {content}
    </main>
  );
}

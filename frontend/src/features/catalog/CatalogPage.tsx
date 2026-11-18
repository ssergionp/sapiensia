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
        <ul className="mt-8 grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
          {items.map((item) => (
            <li key={item.id}>
              {/* the title link stretches over the whole card: one link per card, the card is the target */}
              <article className="relative flex h-full flex-col overflow-hidden rounded-xl border bg-card transition-colors hover:border-primary has-[a:focus-visible]:ring-2 has-[a:focus-visible]:ring-ring has-[a:focus-visible]:ring-offset-2 has-[a:focus-visible]:ring-offset-background">
                <div className="flex justify-center bg-muted/60 px-6 pt-6 pb-4">
                  <CoverImage
                    src={coverUrl(item.id, item.coverVersion)}
                    alt={T.coverAlt(item.title)}
                    category={item.category}
                    title={item.title}
                    className="w-3/5"
                  />
                </div>
                <div className="flex flex-1 flex-col gap-2 p-5">
                  <h2 className="font-sans text-lg leading-snug font-semibold tracking-normal [overflow-wrap:anywhere]">
                    <Link
                      to={`/catalogo/${item.id}`}
                      className="outline-none after:absolute after:inset-0 after:content-['']"
                    >
                      {item.title}
                    </Link>
                  </h2>
                  <p className="line-clamp-3 text-sm text-muted-foreground [overflow-wrap:anywhere]">
                    {item.summary}
                  </p>
                  <div className="mt-auto flex items-end justify-between gap-3 pt-3">
                    <p className="text-sm text-muted-foreground">
                      <span className="sr-only">{T.srCategory} </span>
                      {CATEGORY_LABELS[item.category]}{" "}
                      <span aria-hidden="true">·</span>{" "}
                      <span className="sr-only">{T.srPrice} </span>
                      <span className="text-base font-bold text-foreground tabular-nums">
                        {formatCents(item.priceCents)}
                      </span>
                    </p>
                    <span
                      aria-hidden="true"
                      className="shrink-0 text-sm font-semibold text-primary"
                    >
                      {T.seeDetails}
                    </span>
                  </div>
                </div>
              </article>
            </li>
          ))}
        </ul>
        {totalPages > 1 && (
          <nav
            aria-label={T.paginationLabel}
            className="mt-10 flex items-center justify-center gap-4"
          >
            <Button
              variant="outline"
              disabled={page === 0}
              onClick={() => goTo(page - 1)}
            >
              {T.previous}
            </Button>
            <span className="text-sm tabular-nums">
              {T.pageOf(page + 1, totalPages)}
            </span>
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
    <main className="page-container">
      <h1
        ref={heading}
        tabIndex={-1}
        className="text-4xl outline-none sm:text-5xl"
      >
        {T.listHeading}
      </h1>
      {content}
    </main>
  );
}

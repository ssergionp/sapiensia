import { useEffect, useRef } from "react";
import { Link, useNavigate } from "react-router";
import { Button } from "@/components/ui/button";
import CoverImage from "@/features/catalog/CoverImage";
import { rememberReturnTo } from "@/features/orders/returnTo";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import { NotLoggedInError } from "./api";
import DownloadButton from "./DownloadButton";
import { useLibrary } from "./hooks";
import { LIBRARY_TEXTS as T } from "./messages";
import { libraryCoverUrl } from "./schemas";

const HEADING_ID = "library-heading";

const dateFormat = new Intl.DateTimeFormat("pt-BR", {
  timeZone: "America/Sao_Paulo",
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
});

export default function LibraryPage() {
  const navigate = useNavigate();
  const library = useLibrary();
  const heading = useRef<HTMLHeadingElement>(null);
  const notLoggedIn = library.error instanceof NotLoggedInError;
  const loaded = library.isSuccess;

  useDocumentTitle(T.documentTitle);

  useEffect(() => {
    if (notLoggedIn) {
      rememberReturnTo("/biblioteca");
      navigate("/entrar", { replace: true });
    }
  }, [notLoggedIn, navigate]);

  useEffect(() => {
    if (loaded) heading.current?.focus();
  }, [loaded]);

  let content: React.ReactNode;
  if (library.isPending || notLoggedIn) {
    content = (
      <p role="status" className="mt-4">
        {T.loading}
      </p>
    );
  } else if (library.isError) {
    content = (
      <div className="mt-4">
        <p role="alert" className="text-destructive">
          {T.loadError}
        </p>
        <Button
          variant="outline"
          className="mt-2"
          onClick={() => library.refetch()}
        >
          {T.retry}
        </Button>
      </div>
    );
  } else if (library.data.items.length === 0) {
    content = (
      <p className="mt-4">
        {T.empty}{" "}
        <Link to="/catalogo" className="text-link">
          {T.goToCatalog}
        </Link>
      </p>
    );
  } else {
    content = (
      <ul aria-labelledby={HEADING_ID} className="mt-8 flex flex-col gap-4">
        {library.data.items.map((item) => (
          <li
            key={item.productId}
            className="flex flex-wrap items-center gap-5 rounded-xl border bg-card p-5"
          >
            <CoverImage
              src={libraryCoverUrl(item.productId, item.coverVersion)}
              alt=""
              title={item.title}
              className="w-20 shrink-0 p-1.5 text-[0.6rem] leading-tight"
            />
            <div className="min-w-0 flex-1">
              <p className="font-semibold [overflow-wrap:anywhere]">
                {item.title}
              </p>
              <p className="mt-1 text-sm text-muted-foreground">
                <time dateTime={item.grantedAt}>
                  {T.grantedOn(dateFormat.format(new Date(item.grantedAt)))}
                </time>
              </p>
            </div>
            <div data-slot="actions" className="w-full sm:w-auto sm:max-w-60">
              <DownloadButton productId={item.productId} title={item.title} />
            </div>
          </li>
        ))}
      </ul>
    );
  }

  return (
    <main className="mx-auto w-full max-w-3xl px-4 py-8 sm:px-6 sm:py-12">
      <h1
        ref={heading}
        id={HEADING_ID}
        tabIndex={-1}
        className="text-4xl outline-none sm:text-5xl"
      >
        {T.heading}
      </h1>
      {content}
    </main>
  );
}

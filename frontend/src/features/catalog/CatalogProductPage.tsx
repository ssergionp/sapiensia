import { Check } from "lucide-react";
import { Link, useParams } from "react-router";
import { Button } from "@/components/ui/button";
import { REFUND_POLICY, SUPPORT_EMAIL } from "@/features/legal/legalTexts";
import { useLibrary } from "@/features/library/hooks";
import BuyWithPix from "@/features/orders/BuyWithPix";
import { ORDER_TEXTS } from "@/features/orders/messages";
import { CATEGORY_LABELS } from "@/features/products/messages";
import { formatCents } from "@/features/products/schemas";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import { CatalogProductNotFoundError } from "./api";
import CoverImage from "./CoverImage";
import { useCatalogProduct } from "./hooks";
import { CATALOG_TEXTS as T } from "./messages";
import { coverUrl } from "./schemas";

// A separate component so the Library is fetched only once the Product has loaded.
function PurchaseArea({ productId }: { productId: string }) {
  const library = useLibrary();
  // Pending, error or no session: keep the button; the server answers 409 if the Customer owns it.
  const owned = library.data?.items.some((i) => i.productId === productId);
  if (!owned) return <BuyWithPix productId={productId} />;
  return (
    <div>
      <p>{ORDER_TEXTS.alreadyOwned}</p>
      <p className="mt-2">
        <Link
          to="/biblioteca"
          className="font-semibold text-primary underline underline-offset-4"
        >
          {ORDER_TEXTS.goToLibrary}
        </Link>
      </p>
    </div>
  );
}

export default function CatalogProductPage() {
  const { id = "" } = useParams();
  const query = useCatalogProduct(id);
  const notFound = query.error instanceof CatalogProductNotFoundError;
  let documentTitle: string = T.loadingDocumentTitle;
  if (notFound) documentTitle = T.notFoundDocumentTitle;
  else if (query.isError) documentTitle = T.detailErrorDocumentTitle;
  else if (query.data) documentTitle = T.detailDocumentTitle(query.data.title);
  useDocumentTitle(documentTitle);

  const backLink = (
    <Link
      to="/catalogo"
      className="text-sm font-semibold text-primary underline-offset-4 hover:underline"
    >
      {T.backToCatalog}
    </Link>
  );

  if (query.isPending) {
    return (
      <main className="page-container">
        <p role="status">{T.loading}</p>
      </main>
    );
  }

  if (notFound) {
    return (
      <main className="page-container">
        <h1 className="text-4xl">{T.notFoundHeading}</h1>
        <p className="mt-4">{T.notFoundText}</p>
        <p className="mt-4">{backLink}</p>
      </main>
    );
  }

  if (query.isError) {
    return (
      <main className="page-container">
        <p>{backLink}</p>
        <p role="alert" className="mt-4 text-destructive">
          {T.detailError}
        </p>
        <Button
          variant="outline"
          className="mt-2"
          onClick={() => query.refetch()}
        >
          {T.retry}
        </Button>
      </main>
    );
  }

  const product = query.data;
  return (
    <main className="page-container">
      <p>{backLink}</p>
      <div className="mt-6 grid gap-8 md:grid-cols-[minmax(0,2fr)_minmax(0,3fr)] lg:gap-14">
        <div className="mx-auto w-full max-w-xs md:max-w-none">
          <CoverImage
            src={coverUrl(product.id, product.coverVersion)}
            alt={T.coverAlt(product.title)}
            category={product.category}
            title={product.title}
            className="rounded-xl"
          />
        </div>
        <div className="min-w-0">
          <h1 className="text-4xl leading-tight [overflow-wrap:anywhere] sm:text-5xl">
            {product.title}
          </h1>
          <dl className="mt-6 grid grid-cols-[auto_1fr] items-baseline gap-x-4 gap-y-2">
            <dt className="text-sm text-muted-foreground">{T.categoryTerm}</dt>
            <dd className="font-semibold">
              {CATEGORY_LABELS[product.category]}
            </dd>
            <dt className="text-sm text-muted-foreground">{T.priceTerm}</dt>
            <dd className="text-3xl font-bold tabular-nums">
              {formatCents(product.priceCents)}
            </dd>
          </dl>
          <section className="mt-8 rounded-xl border bg-card p-6">
            <PurchaseArea productId={product.id} />
            <ul className="mt-6 space-y-2 text-sm">
              {T.purchaseBenefits.map((benefit) => (
                <li key={benefit} className="flex gap-2">
                  <Check
                    aria-hidden="true"
                    className="mt-0.5 size-4 shrink-0 text-primary"
                  />
                  <span>{benefit}</span>
                </li>
              ))}
            </ul>
            <div className="mt-5 space-y-1 border-t pt-4 text-sm text-muted-foreground">
              <p>
                {T.refundBefore}{" "}
                <Link
                  to={REFUND_POLICY.path}
                  className="font-semibold text-primary underline underline-offset-4"
                >
                  {REFUND_POLICY.title}
                </Link>
                .
              </p>
              <p>
                {T.supportBefore}{" "}
                <a
                  href={`mailto:${SUPPORT_EMAIL}`}
                  className="font-semibold text-primary underline underline-offset-4 [overflow-wrap:anywhere]"
                >
                  {SUPPORT_EMAIL}
                </a>
              </p>
            </div>
          </section>
          <p className="mt-8 max-w-prose whitespace-pre-line [overflow-wrap:anywhere]">
            {product.description}
          </p>
        </div>
      </div>
    </main>
  );
}

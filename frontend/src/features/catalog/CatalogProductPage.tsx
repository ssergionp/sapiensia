import { Link, useParams } from "react-router";
import { Button } from "@/components/ui/button";
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
    <div className="mt-6">
      <p>{ORDER_TEXTS.alreadyOwned}</p>
      <p className="mt-2">
        <Link to="/biblioteca" className="underline">
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
    <Link to="/catalogo" className="underline">
      {T.backToCatalog}
    </Link>
  );

  if (query.isPending) {
    return (
      <main className="p-8">
        <p role="status">{T.loading}</p>
      </main>
    );
  }

  if (notFound) {
    return (
      <main className="p-8">
        <h1 className="text-2xl font-bold">{T.notFoundHeading}</h1>
        <p className="mt-4">{T.notFoundText}</p>
        <p className="mt-4">{backLink}</p>
      </main>
    );
  }

  if (query.isError) {
    return (
      <main className="p-8">
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
    <main className="p-8">
      <p>{backLink}</p>
      <div className="mt-4 flex flex-col gap-4 sm:flex-row sm:items-start">
        <CoverImage
          src={coverUrl(product.id, product.coverVersion)}
          alt={T.coverAlt(product.title)}
          className="w-40 shrink-0"
        />
        <div className="min-w-0">
          <h1 className="text-2xl font-bold [overflow-wrap:anywhere]">
            {product.title}
          </h1>
          <dl className="mt-4 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
            <dt className="font-semibold">{T.categoryTerm}</dt>
            <dd>{CATEGORY_LABELS[product.category]}</dd>
            <dt className="font-semibold">{T.priceTerm}</dt>
            <dd>{formatCents(product.priceCents)}</dd>
          </dl>
          <p className="mt-4 whitespace-pre-line [overflow-wrap:anywhere]">
            {product.description}
          </p>
          <PurchaseArea productId={product.id} />
        </div>
      </div>
    </main>
  );
}

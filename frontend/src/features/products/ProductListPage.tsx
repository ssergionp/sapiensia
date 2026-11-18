import { cn } from "cn";
import { useEffect, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router";
import StatusBadge from "@/components/StatusBadge";
import { Button, buttonVariants } from "@/components/ui/button";
import AdminGuard from "@/features/admin/AdminGuard";
import { TABLE, TABLE_HEAD, TABLE_REGION } from "@/features/admin/tableStyles";
import { ProductForbiddenError, ProductUnavailableError } from "./api";
import { useProducts } from "./hooks";
import {
  CATEGORY_LABELS,
  NO_PERMISSION_PRODUCTS,
  STATUS_LABELS,
  UNAVAILABLE_LOAD,
} from "./messages";
import { formatCents } from "./schemas";
import { PRODUCT_STATUS_TONE } from "./statusTone";

const HEADING_ID = "products-heading";

const LOAD_ERROR =
  "Não foi possível carregar os Produtos agora. Tente novamente.";
const EMPTY =
  'Nenhum Produto cadastrado ainda. Use "Novo Produto" para criar o primeiro.';

function loadErrorMessage(error: unknown) {
  if (error instanceof ProductForbiddenError) return NO_PERMISSION_PRODUCTS;
  if (error instanceof ProductUnavailableError) return UNAVAILABLE_LOAD;
  return LOAD_ERROR;
}

function ProductList() {
  const [page, setPage] = useState(0);
  const location = useLocation();
  const navigate = useNavigate();
  // Kept in component state so the notice survives clearing the history state.
  const [created, setCreated] = useState(
    () => (location.state as { created?: boolean } | null)?.created === true,
  );
  // Clear the router state so a reload of the list does not show the notice again.
  useEffect(() => {
    if ((location.state as { created?: boolean } | null)?.created) {
      setCreated(true);
      navigate(location.pathname, { replace: true, state: null });
    }
  }, [location.pathname, location.state, navigate]);
  const products = useProducts(page);

  let content: React.ReactNode;
  if (products.isPending) {
    content = <p className="mt-4">Carregando…</p>;
  } else if (products.isError) {
    content = (
      <p role="alert" className="mt-4 text-destructive">
        {loadErrorMessage(products.error)}
      </p>
    );
  } else if (products.data.items.length === 0) {
    content = <p className="mt-4">{EMPTY}</p>;
  } else {
    const { items, totalPages } = products.data;
    content = (
      <>
        {/* Scrollable region: focusable so the keyboard can scroll it (WCAG 2.1.1). */}
        <section
          aria-labelledby={HEADING_ID}
          // biome-ignore lint/a11y/noNoninteractiveTabindex: scrollable region must be keyboard-focusable
          tabIndex={0}
          className={TABLE_REGION}
        >
          <table className={TABLE}>
            <thead className={TABLE_HEAD}>
              <tr>
                <th scope="col" className="min-w-48 px-4 py-3">
                  Título
                </th>
                <th scope="col" className="px-4 py-3">
                  Categoria
                </th>
                <th scope="col" className="px-4 py-3">
                  Preço
                </th>
                <th scope="col" className="px-4 py-3">
                  Estado
                </th>
                <th scope="col" className="px-4 py-3">
                  Ações
                </th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id} className="border-t">
                  <td className="min-w-48 px-4 py-3 font-semibold [overflow-wrap:anywhere]">
                    {item.title}
                  </td>
                  <td className="px-4 py-3">
                    {CATEGORY_LABELS[item.category]}
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap tabular-nums">
                    {formatCents(item.priceCents)}
                  </td>
                  <td className="px-4 py-3">
                    <StatusBadge
                      status={item.status}
                      tone={PRODUCT_STATUS_TONE[item.status]}
                    >
                      {STATUS_LABELS[item.status]}
                    </StatusBadge>
                  </td>
                  <td className="px-4 py-3">
                    <Link
                      to={`/painel/produtos/${item.id}`}
                      aria-label={`Editar ${item.title}`}
                      className="text-link"
                    >
                      Editar
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
        <nav
          aria-label="Paginação"
          className="mt-4 flex flex-wrap items-center gap-4"
        >
          <Button
            variant="outline"
            disabled={page === 0}
            onClick={() => setPage(page - 1)}
          >
            Anterior
          </Button>
          <span className="text-sm text-muted-foreground tabular-nums">
            Página {page + 1} de {Math.max(totalPages, 1)}
          </span>
          <Button
            variant="outline"
            disabled={page + 1 >= totalPages}
            onClick={() => setPage(page + 1)}
          >
            Próxima
          </Button>
        </nav>
      </>
    );
  }

  return (
    <main className="page-container">
      <h1 id={HEADING_ID} className="text-3xl">
        Produtos
      </h1>
      <p className="mt-4 flex flex-wrap items-center gap-x-6 gap-y-3">
        <Link
          to="/painel/produtos/novo"
          className={cn(buttonVariants(), "h-11 px-4 text-base")}
        >
          Novo Produto
        </Link>
        <Link to="/painel" className="text-link">
          Voltar ao painel
        </Link>
      </p>
      {created && (
        <p role="status" className="mt-4 rounded-lg bg-muted p-4 text-sm">
          Produto criado.
        </p>
      )}
      {content}
    </main>
  );
}

export default function ProductListPage() {
  return (
    <AdminGuard
      capability="PRODUCTS_EDIT"
      missingMessage={NO_PERMISSION_PRODUCTS}
    >
      {() => <ProductList />}
    </AdminGuard>
  );
}

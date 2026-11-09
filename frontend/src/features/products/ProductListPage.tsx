import { useState } from "react";
import { Link, useLocation } from "react-router";
import { Button } from "@/components/ui/button";
import AdminGuard from "@/features/admin/AdminGuard";
import { ProductForbiddenError, ProductUnavailableError } from "./api";
import { useProducts } from "./hooks";
import {
  CATEGORY_LABELS,
  NO_PERMISSION_PRODUCTS,
  UNAVAILABLE_LOAD,
} from "./messages";
import { formatCents } from "./schemas";

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
  const created = (location.state as { created?: boolean } | null)?.created;
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
        <table className="mt-4 w-full text-left text-sm">
          <thead>
            <tr>
              <th scope="col" className="py-2 pr-4">
                Título
              </th>
              <th scope="col" className="py-2 pr-4">
                Categoria
              </th>
              <th scope="col" className="py-2 pr-4">
                Preço
              </th>
              <th scope="col" className="py-2 pr-4">
                Estado
              </th>
              <th scope="col" className="py-2">
                Ações
              </th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.id} className="border-t">
                <td className="py-2 pr-4 [overflow-wrap:anywhere]">
                  {item.title}
                </td>
                <td className="py-2 pr-4">{CATEGORY_LABELS[item.category]}</td>
                <td className="py-2 pr-4">{formatCents(item.priceCents)}</td>
                <td className="py-2 pr-4">Rascunho</td>
                <td className="py-2">
                  <Link
                    to={`/painel/produtos/${item.id}`}
                    aria-label={`Editar ${item.title}`}
                    className="underline"
                  >
                    Editar
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <nav aria-label="Paginação" className="mt-4 flex items-center gap-4">
          <Button
            variant="outline"
            disabled={page === 0}
            onClick={() => setPage(page - 1)}
          >
            Anterior
          </Button>
          <span>
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
    <main className="p-8">
      <h1 className="text-2xl font-bold">Produtos</h1>
      <p className="mt-4 flex gap-4">
        <Link to="/painel/produtos/novo" className="underline">
          Novo Produto
        </Link>
        <Link to="/painel" className="underline">
          Voltar ao painel
        </Link>
      </p>
      {created && (
        <p role="status" className="mt-4">
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

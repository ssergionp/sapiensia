import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import { Button } from "@/components/ui/button";
import AdminGuard from "@/features/admin/AdminGuard";
import { formatCents } from "@/features/products/schemas";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import { AdminOrdersForbiddenError } from "./api";
import { useAdminOrders } from "./hooks";
import { STATUS_TEXT, ADMIN_ORDERS_TEXTS as T } from "./messages";
import { ORDER_STATUSES, type OrderStatus } from "./schemas";

const HEADING_ID = "admin-orders-heading";

const dateTimeParts = new Intl.DateTimeFormat("pt-BR", {
  timeZone: "America/Sao_Paulo",
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

// "09/11/2026 14:32" in Brasilia time, whatever the zone of the browser.
function formatDateTime(iso: string): string {
  const parts: Record<string, string> = {};
  for (const part of dateTimeParts.formatToParts(new Date(iso))) {
    parts[part.type] = part.value;
  }
  return `${parts.day}/${parts.month}/${parts.year} ${parts.hour}:${parts.minute}`;
}

function DateCell({ iso }: { iso: string | null }) {
  if (iso === null) return <>{T.notPaid}</>;
  return <time dateTime={iso}>{formatDateTime(iso)}</time>;
}

function AdminOrders() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<OrderStatus | null>(null);
  const orders = useAdminOrders(page, status);
  const heading = useRef<HTMLHeadingElement>(null);
  const loaded = orders.isSuccess;

  useDocumentTitle(T.documentTitle);

  useEffect(() => {
    if (loaded) heading.current?.focus();
  }, [loaded]);

  let content: React.ReactNode;
  if (orders.isPending) {
    content = (
      <p role="status" className="mt-4">
        {T.loading}
      </p>
    );
  } else if (orders.isError) {
    content = (
      <div className="mt-4">
        <p role="alert" className="text-destructive">
          {orders.error instanceof AdminOrdersForbiddenError
            ? T.noPermission
            : T.loadError}
        </p>
        {!(orders.error instanceof AdminOrdersForbiddenError) && (
          <Button
            variant="outline"
            className="mt-2"
            onClick={() => orders.refetch()}
          >
            {T.retry}
          </Button>
        )}
      </div>
    );
  } else if (orders.data.items.length === 0) {
    content = (
      <p className="mt-4">
        {status === null ? T.emptyNoFilter : T.emptyWithFilter}
      </p>
    );
  } else {
    const { items, totalPages } = orders.data;
    content = (
      <>
        {/* Scrollable region: focusable so the keyboard can scroll it (WCAG 2.1.1). */}
        <section
          aria-labelledby={HEADING_ID}
          // biome-ignore lint/a11y/noNoninteractiveTabindex: scrollable region must be keyboard-focusable
          tabIndex={0}
          className="mt-4 overflow-x-auto"
        >
          <table className="w-full text-left text-sm">
            <thead>
              <tr>
                {Object.values(T.columns).map((name) => (
                  <th key={name} scope="col" className="py-2 pr-4">
                    {name}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id} className="border-t">
                  <td className="py-2 pr-4 font-mono">{item.id.slice(0, 8)}</td>
                  <td className="min-w-48 py-2 pr-4 [overflow-wrap:anywhere]">
                    {item.productTitle}
                  </td>
                  <td className="py-2 pr-4 whitespace-nowrap">
                    {formatCents(item.priceCents)}
                  </td>
                  <td className="py-2 pr-4">{STATUS_TEXT[item.status]}</td>
                  <td className="py-2 pr-4 [overflow-wrap:anywhere]">
                    {item.customerEmail}
                  </td>
                  <td className="py-2 pr-4 whitespace-nowrap">
                    <DateCell iso={item.createdAt} />
                  </td>
                  <td className="py-2 whitespace-nowrap">
                    <DateCell iso={item.paidAt} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
        <nav aria-label={T.pagination} className="mt-4 flex items-center gap-4">
          <Button
            variant="outline"
            disabled={page === 0}
            onClick={() => setPage(page - 1)}
          >
            {T.previous}
          </Button>
          <span aria-live="polite">
            {T.pageOf(page + 1, Math.max(totalPages, 1))}
          </span>
          <Button
            variant="outline"
            disabled={page + 1 >= totalPages}
            onClick={() => setPage(page + 1)}
          >
            {T.next}
          </Button>
        </nav>
      </>
    );
  }

  return (
    <main className="p-8">
      <h1
        ref={heading}
        id={HEADING_ID}
        tabIndex={-1}
        className="text-2xl font-bold [overflow-wrap:anywhere]"
      >
        {T.heading}
      </h1>
      <p className="mt-4">
        <Link to="/painel" className="underline">
          {T.backToPanel}
        </Link>
      </p>
      <div className="mt-4">
        <label htmlFor="admin-orders-status" className="mr-2 text-sm">
          {T.filterLabel}
        </label>
        <select
          id="admin-orders-status"
          className="rounded-md border bg-background px-2 py-1 text-sm"
          value={status ?? ""}
          onChange={(event) => {
            setStatus((event.target.value || null) as OrderStatus | null);
            setPage(0);
          }}
        >
          <option value="">{T.filterAll}</option>
          {ORDER_STATUSES.map((value) => (
            <option key={value} value={value}>
              {STATUS_TEXT[value]}
            </option>
          ))}
        </select>
      </div>
      {content}
    </main>
  );
}

export default function AdminOrdersPage() {
  return (
    <AdminGuard capability="ORDERS_VIEW" missingMessage={T.noPermission}>
      {() => <AdminOrders />}
    </AdminGuard>
  );
}

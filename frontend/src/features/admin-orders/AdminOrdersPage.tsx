import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import StatusBadge from "@/components/StatusBadge";
import { Button } from "@/components/ui/button";
import AdminGuard from "@/features/admin/AdminGuard";
import {
  SELECT,
  TABLE,
  TABLE_HEAD,
  TABLE_REGION,
} from "@/features/admin/tableStyles";
import { ORDER_STATUS_TONE } from "@/features/orders/statusTone";
import { formatCents } from "@/features/products/schemas";
import { saoPauloDateAndTime } from "@/lib/saoPauloDate";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import { AdminOrdersForbiddenError } from "./api";
import { useAdminOrders } from "./hooks";
import { STATUS_TEXT, ADMIN_ORDERS_TEXTS as T } from "./messages";
import { ORDER_STATUSES, type OrderStatus } from "./schemas";

const HEADING_ID = "admin-orders-heading";

// "09/11/2026 14:32" in Brasilia time.
function formatDateTime(iso: string): string {
  const { date, time } = saoPauloDateAndTime(iso);
  return `${date} ${time}`;
}

function DateCell({ iso }: { iso: string | null }) {
  if (iso === null) return <>{T.notPaid}</>;
  return <time dateTime={iso}>{formatDateTime(iso)}</time>;
}

function Pagination({
  page,
  totalPages,
  onPage,
}: {
  page: number;
  totalPages: number;
  onPage: (page: number) => void;
}) {
  return (
    <nav
      aria-label={T.pagination}
      className="mt-4 flex flex-wrap items-center gap-4"
    >
      <Button
        variant="outline"
        disabled={page === 0}
        onClick={() => onPage(page - 1)}
      >
        {T.previous}
      </Button>
      <span
        aria-live="polite"
        className="text-sm text-muted-foreground tabular-nums"
      >
        {T.pageOf(page + 1, Math.max(totalPages, 1))}
      </span>
      <Button
        variant="outline"
        disabled={page + 1 >= totalPages}
        onClick={() => onPage(page + 1)}
      >
        {T.next}
      </Button>
    </nav>
  );
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
    // A page past the end (Orders left the filter meanwhile) keeps the way back.
    content = (
      <>
        <p className="mt-4">
          {status === null ? T.emptyNoFilter : T.emptyWithFilter}
        </p>
        {page > 0 && (
          <Pagination
            page={page}
            totalPages={orders.data.totalPages}
            onPage={setPage}
          />
        )}
      </>
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
          className={TABLE_REGION}
        >
          <table className={TABLE}>
            <thead className={TABLE_HEAD}>
              <tr>
                {Object.values(T.columns).map((name) => (
                  <th key={name} scope="col" className="px-4 py-3">
                    {name}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id} className="border-t">
                  <td className="px-4 py-3 font-mono">{item.id.slice(0, 8)}</td>
                  <td className="min-w-48 px-4 py-3 font-semibold [overflow-wrap:anywhere]">
                    {item.productTitle}
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap tabular-nums">
                    {formatCents(item.priceCents)}
                  </td>
                  <td className="px-4 py-3">
                    <StatusBadge
                      status={item.status}
                      tone={ORDER_STATUS_TONE[item.status]}
                    >
                      {STATUS_TEXT[item.status]}
                    </StatusBadge>
                  </td>
                  <td className="min-w-48 px-4 py-3 [overflow-wrap:anywhere]">
                    {item.customerEmail}
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap tabular-nums">
                    <DateCell iso={item.createdAt} />
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap tabular-nums">
                    <DateCell iso={item.paidAt} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
        <Pagination page={page} totalPages={totalPages} onPage={setPage} />
      </>
    );
  }

  return (
    <main className="page-container">
      <h1
        ref={heading}
        id={HEADING_ID}
        tabIndex={-1}
        className="text-3xl outline-none [overflow-wrap:anywhere]"
      >
        {T.heading}
      </h1>
      <p className="mt-4">
        <Link to="/painel" className="text-link">
          {T.backToPanel}
        </Link>
      </p>
      <div className="mt-6 flex flex-wrap items-center gap-x-3 gap-y-2">
        <label htmlFor="admin-orders-status" className="text-sm font-semibold">
          {T.filterLabel}
        </label>
        <select
          id="admin-orders-status"
          className={SELECT}
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

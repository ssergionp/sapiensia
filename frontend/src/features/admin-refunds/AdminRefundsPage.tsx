import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import { Button } from "@/components/ui/button";
import AdminGuard from "@/features/admin/AdminGuard";
import {
  SELECT,
  TABLE,
  TABLE_HEAD,
  TABLE_REGION,
} from "@/features/admin/tableStyles";
import { formatCents } from "@/features/products/schemas";
import { saoPauloDateAndTime } from "@/lib/saoPauloDate";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import { AdminRefundsForbiddenError } from "./api";
import { useAdminRefunds } from "./hooks";
import { ADMIN_REFUNDS_TEXTS as T } from "./messages";
import type { RefundFilter } from "./schemas";

const HEADING_ID = "admin-refunds-heading";

type FilterValue = RefundFilter | "ALL";

const EMPTY_TEXT: Record<FilterValue, string> = {
  OPEN: T.emptyOpen,
  REFUNDED: T.emptyRefunded,
  ALL: T.emptyAll,
};

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

function AdminRefunds() {
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState<FilterValue>("OPEN");
  const refunds = useAdminRefunds(page, filter === "ALL" ? null : filter);
  const heading = useRef<HTMLHeadingElement>(null);
  const loaded = refunds.isSuccess;

  useDocumentTitle(T.documentTitle);

  useEffect(() => {
    if (loaded) heading.current?.focus();
  }, [loaded]);

  let content: React.ReactNode;
  if (refunds.isPending) {
    content = (
      <p role="status" className="mt-4">
        {T.loading}
      </p>
    );
  } else if (refunds.isError) {
    const forbidden = refunds.error instanceof AdminRefundsForbiddenError;
    content = (
      <div className="mt-4">
        <p role="alert" className="text-destructive">
          {forbidden ? T.noPermission : T.loadError}
        </p>
        {!forbidden && (
          <Button
            variant="outline"
            className="mt-2"
            onClick={() => refunds.refetch()}
          >
            {T.retry}
          </Button>
        )}
      </div>
    );
  } else if (refunds.data.items.length === 0) {
    // A page past the end keeps the way back.
    content = (
      <>
        <p className="mt-4">{EMPTY_TEXT[filter]}</p>
        {page > 0 && (
          <Pagination
            page={page}
            totalPages={refunds.data.totalPages}
            onPage={setPage}
          />
        )}
      </>
    );
  } else {
    const { items, totalPages } = refunds.data;
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
                <tr key={item.orderId} className="border-t align-top">
                  <td className="px-4 py-3 font-mono">
                    {item.orderId.slice(0, 8)}
                  </td>
                  <td className="min-w-48 px-4 py-3 font-semibold [overflow-wrap:anywhere]">
                    {item.productTitle}
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap tabular-nums">
                    {formatCents(item.priceCents)}
                  </td>
                  <td className="min-w-48 px-4 py-3 [overflow-wrap:anywhere]">
                    {item.customerEmail}
                  </td>
                  <td className="min-w-64 px-4 py-3 [overflow-wrap:anywhere]">
                    <p>{T.reasons[item.reason]}</p>
                    {item.details !== null && (
                      <p className="mt-1 text-muted-foreground">
                        {T.customerComment(item.details)}
                      </p>
                    )}
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap tabular-nums">
                    <DateCell iso={item.requestedAt} />
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap tabular-nums">
                    <DateCell iso={item.paidAt} />
                  </td>
                  <td className="px-4 py-3 tabular-nums">{item.downloads}</td>
                  <td className="px-4 py-3 tabular-nums">
                    {item.otherRefunds}
                  </td>
                  <td className="px-4 py-3 font-mono select-all">
                    {item.chargeId ?? T.notPaid}
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap">
                    {item.refundedAt === null ? (
                      T.statusOpen
                    ) : (
                      <time dateTime={item.refundedAt}>
                        {T.statusRefundedAt(formatDateTime(item.refundedAt))}
                      </time>
                    )}
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
      <p className="mt-4 max-w-3xl">{T.guidance}</p>
      <div className="mt-6 flex flex-wrap items-center gap-x-3 gap-y-2">
        <label htmlFor="admin-refunds-status" className="text-sm font-semibold">
          {T.filterLabel}
        </label>
        <select
          id="admin-refunds-status"
          className={SELECT}
          value={filter}
          onChange={(event) => {
            setFilter(event.target.value as FilterValue);
            setPage(0);
          }}
        >
          <option value="OPEN">{T.filterOpen}</option>
          <option value="REFUNDED">{T.filterRefunded}</option>
          <option value="ALL">{T.filterAll}</option>
        </select>
      </div>
      {content}
    </main>
  );
}

export default function AdminRefundsPage() {
  return (
    <AdminGuard capability="REFUNDS_VIEW" missingMessage={T.noPermission}>
      {() => <AdminRefunds />}
    </AdminGuard>
  );
}

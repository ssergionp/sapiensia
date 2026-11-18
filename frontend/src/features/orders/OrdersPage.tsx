import { cn } from "cn";
import { useEffect, useRef } from "react";
import { Link, useNavigate } from "react-router";
import StatusBadge from "@/components/StatusBadge";
import { Button, buttonVariants } from "@/components/ui/button";
import { formatCents } from "@/features/products/schemas";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import { NotLoggedInError } from "./api";
import { useOrders } from "./hooks";
import { ORDER_TEXTS as T } from "./messages";
import { rememberReturnTo } from "./returnTo";
import type { OrderList } from "./schemas";
import { ORDER_STATUS_TONE } from "./statusTone";

const HEADING_ID = "orders-heading";

const dateFormat = new Intl.DateTimeFormat("pt-BR", {
  timeZone: "America/Sao_Paulo",
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
});

const timeFormat = new Intl.DateTimeFormat("pt-BR", {
  timeZone: "America/Sao_Paulo",
  hour: "2-digit",
  minute: "2-digit",
});

const STATUS_TEXT = {
  PENDING: T.statusPending,
  PAID: T.statusPaid,
  EXPIRED: T.statusExpired,
  REFUNDED: T.statusRefunded,
} as const;

function OrderItem({ order }: { order: OrderList["items"][number] }) {
  return (
    <li className="rounded-xl border bg-card p-5">
      <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
        <p className="min-w-0 font-semibold [overflow-wrap:anywhere]">
          {order.productTitle}
        </p>
        <StatusBadge
          status={order.status}
          tone={ORDER_STATUS_TONE[order.status]}
        >
          {STATUS_TEXT[order.status]}
        </StatusBadge>
      </div>
      <p className="mt-1 text-sm text-muted-foreground">
        <span className="font-semibold text-foreground tabular-nums">
          {formatCents(order.priceCents)}
        </span>{" "}
        <span aria-hidden="true">·</span>{" "}
        <time dateTime={order.createdAt}>
          {T.orderedOn(dateFormat.format(new Date(order.createdAt)))}
        </time>
      </p>
      {order.status === "PENDING" && (
        <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-2">
          <p className="text-sm">
            <time dateTime={order.expiresAt}>
              {T.pixValidUntil(timeFormat.format(new Date(order.expiresAt)))}
            </time>
          </p>
          <Link
            to={`/pedidos/${order.id}`}
            aria-label={T.viewPixLabel(order.productTitle)}
            className={cn(buttonVariants(), "h-10 px-4")}
          >
            {T.viewPix}
          </Link>
        </div>
      )}
      {order.status === "PAID" && (
        <Link to="/biblioteca" className="text-link mt-3 inline-block text-sm">
          {T.goToLibrary}
        </Link>
      )}
    </li>
  );
}

export default function OrdersPage() {
  const navigate = useNavigate();
  const orders = useOrders();
  const heading = useRef<HTMLHeadingElement>(null);
  const notLoggedIn = orders.error instanceof NotLoggedInError;
  const loaded = orders.isSuccess;

  useDocumentTitle(T.listDocumentTitle);

  useEffect(() => {
    if (notLoggedIn) {
      rememberReturnTo("/pedidos");
      navigate("/entrar", { replace: true });
    }
  }, [notLoggedIn, navigate]);

  useEffect(() => {
    if (loaded) heading.current?.focus();
  }, [loaded]);

  let content: React.ReactNode;
  if (orders.isPending || notLoggedIn) {
    content = (
      <p role="status" className="mt-4">
        {T.loading}
      </p>
    );
  } else if (orders.isError) {
    content = (
      <div className="mt-4">
        <p role="alert" className="text-destructive">
          {T.listLoadError}
        </p>
        <Button
          variant="outline"
          className="mt-2"
          onClick={() => orders.refetch()}
        >
          {T.retry}
        </Button>
      </div>
    );
  } else if (orders.data.items.length === 0) {
    content = (
      <p className="mt-4">
        {T.listEmpty}{" "}
        <Link to="/catalogo" className="text-link">
          {T.goToCatalog}
        </Link>
      </p>
    );
  } else {
    content = (
      <ul aria-labelledby={HEADING_ID} className="mt-8 flex flex-col gap-4">
        {orders.data.items.map((order) => (
          <OrderItem key={order.id} order={order} />
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
        {T.listHeading}
      </h1>
      {content}
    </main>
  );
}

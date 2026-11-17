import { useEffect, useRef } from "react";
import { Link, useNavigate } from "react-router";
import { Button } from "@/components/ui/button";
import { formatCents } from "@/features/products/schemas";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import { NotLoggedInError } from "./api";
import { useOrders } from "./hooks";
import { ORDER_TEXTS as T } from "./messages";
import { rememberReturnTo } from "./returnTo";
import type { OrderList } from "./schemas";

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
    <li className="rounded-lg border p-4">
      <p className="font-semibold [overflow-wrap:anywhere]">
        {order.productTitle}
      </p>
      <p className="mt-1 text-sm">
        {formatCents(order.priceCents)} <span aria-hidden="true">·</span>{" "}
        <time dateTime={order.createdAt}>
          {T.orderedOn(dateFormat.format(new Date(order.createdAt)))}
        </time>
      </p>
      <p className="mt-2 text-sm font-medium">{STATUS_TEXT[order.status]}</p>
      {order.status === "PENDING" && (
        <>
          <p className="mt-1 text-sm">
            <time dateTime={order.expiresAt}>
              {T.pixValidUntil(timeFormat.format(new Date(order.expiresAt)))}
            </time>
          </p>
          <Link
            to={`/pedidos/${order.id}`}
            aria-label={T.viewPixLabel(order.productTitle)}
            className="mt-1 inline-block text-sm underline"
          >
            {T.viewPix}
          </Link>
        </>
      )}
      {order.status === "PAID" && (
        <Link to="/biblioteca" className="mt-1 inline-block text-sm underline">
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
        <Link to="/catalogo" className="underline">
          {T.goToCatalog}
        </Link>
      </p>
    );
  } else {
    content = (
      <ul aria-labelledby={HEADING_ID} className="mt-4 flex flex-col gap-4">
        {orders.data.items.map((order) => (
          <OrderItem key={order.id} order={order} />
        ))}
      </ul>
    );
  }

  return (
    <main className="mx-auto max-w-2xl p-8">
      <h1
        ref={heading}
        id={HEADING_ID}
        tabIndex={-1}
        className="text-2xl font-bold"
      >
        {T.listHeading}
      </h1>
      {content}
    </main>
  );
}

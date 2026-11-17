import { QRCodeSVG } from "qrcode.react";
import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { Button } from "@/components/ui/button";
import { formatCents } from "@/features/products/schemas";
import { TOO_MANY_REQUESTS, TooManyRequestsError } from "@/lib/tooManyRequests";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import { NotLoggedInError, OrderNotFoundError } from "./api";
import { useOrder, useOrderStatus } from "./hooks";
import { ORDER_TEXTS as T } from "./messages";
import { rememberReturnTo } from "./returnTo";

const EXPIRED_WATCH_MS = 10 * 60 * 1000;

function mmss(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

export default function OrderPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const query = useOrder(id);
  const refetchOrder = query.refetch;
  const headingRef = useRef<HTMLHeadingElement>(null);
  const deadline = useRef(0);
  const previous = useRef(0);
  const [remaining, setRemaining] = useState<number | null>(null);
  const [announcement, setAnnouncement] = useState("");
  const [copy, setCopy] = useState<"idle" | "done" | "failed">("idle");

  const order = query.data;
  const notFound = query.error instanceof OrderNotFoundError;
  const pending = order?.status === "PENDING" && !query.isError;
  const expired = order?.status === "EXPIRED" && !query.isError;
  const paid = order?.status === "PAID" && !query.isError;
  const refunded = order?.status === "REFUNDED" && !query.isError;

  // After EXPIRED the page keeps asking for 10 minutes, counted from when the expired view appeared here.
  const [expiredWindowOver, setExpiredWindowOver] = useState(false);
  // Keyed on the Order's status alone: a failed reload (isError) must not restart the window.
  const statusIsExpired = order?.status === "EXPIRED";
  useEffect(() => {
    if (!statusIsExpired) return;
    const shownAt = performance.now();
    const timer = setInterval(() => {
      if (performance.now() - shownAt >= EXPIRED_WATCH_MS) {
        setExpiredWindowOver(true);
      }
    }, 1000);
    return () => clearInterval(timer);
  }, [statusIsExpired]);

  const statusQuery = useOrderStatus(
    id,
    pending || (expired && !expiredWindowOver),
  );
  // Only a 401 of the polling matters (the session ended while waiting); any other polling error is ignored:
  // the next tick tries again and the page keeps what it shows.
  const notLoggedIn =
    query.error instanceof NotLoggedInError ||
    statusQuery.error instanceof NotLoggedInError;
  const polledStatus = statusQuery.data;
  const polledAt = statusQuery.dataUpdatedAt;
  const shownStatus = order?.status;
  // biome-ignore lint/correctness/useExhaustiveDependencies: run on each answer that differs from what is shown
  useEffect(() => {
    if (polledStatus && shownStatus && polledStatus !== shownStatus) {
      void refetchOrder({ cancelRefetch: false });
    }
  }, [polledAt, polledStatus, shownStatus]);

  let documentTitle: string = T.loadingDocumentTitle;
  if (notFound) documentTitle = T.notFoundDocumentTitle;
  else if (query.isError && !notLoggedIn) documentTitle = T.documentTitle;
  else if (paid) documentTitle = T.paidDocumentTitle;
  else if (expired) documentTitle = T.expiredDocumentTitle;
  else if (refunded) documentTitle = T.refundedDocumentTitle;
  else if (pending) documentTitle = T.documentTitle;
  useDocumentTitle(documentTitle);

  useEffect(() => {
    if (notLoggedIn) {
      rememberReturnTo(`/pedidos/${id}`);
      navigate("/entrar", { replace: true });
    }
  }, [notLoggedIn, id, navigate]);

  // The countdown starts from the server's seconds and runs against a fixed deadline, never the device's wall clock.
  const arrival = query.dataUpdatedAt;
  useEffect(() => {
    if (arrival > 0 && order?.status === "PENDING") {
      deadline.current = performance.now() + order.secondsToExpire * 1000;
      previous.current = order.secondsToExpire;
      setRemaining(order.secondsToExpire);
      setAnnouncement("");
    }
  }, [arrival, order?.status, order?.secondsToExpire]);

  useEffect(() => {
    if (!pending) return;
    const timer = setInterval(() => {
      const left = Math.max(
        0,
        Math.ceil((deadline.current - performance.now()) / 1000),
      );
      if (previous.current > 300 && left <= 300) {
        setAnnouncement(T.warnFiveMinutes);
      }
      if (previous.current > 60 && left <= 60) {
        setAnnouncement(T.warnOneMinute);
      }
      previous.current = left;
      setRemaining(left);
    }, 1000);
    return () => clearInterval(timer);
  }, [pending]);

  // At zero the server decides: keep asking until it says EXPIRED (or the query fails). Never expire client-side.
  const refetch = query.refetch;
  const atZero = pending && remaining === 0;
  useEffect(() => {
    if (!atZero) return;
    void refetch();
    const timer = setInterval(() => void refetch(), 2000);
    return () => clearInterval(timer);
  }, [atZero, refetch]);

  const view = pending
    ? "pending"
    : expired
      ? "expired"
      : paid
        ? "paid"
        : refunded
          ? "refunded"
          : "other";
  // biome-ignore lint/correctness/useExhaustiveDependencies: focus the heading when the view changes
  useEffect(() => {
    if (view !== "other") headingRef.current?.focus();
  }, [view]);

  async function copyCode(code: string) {
    try {
      await navigator.clipboard.writeText(code);
      setCopy("done");
    } catch {
      setCopy("failed");
    }
  }

  const catalogLink = (
    <Link to="/catalogo" className="underline">
      {T.backToCatalog}
    </Link>
  );

  if (notFound) {
    return (
      <main className="p-8">
        <h1 className="text-2xl font-bold">{T.notFoundHeading}</h1>
        <p className="mt-4">{T.notFoundText}</p>
        <p className="mt-4">{catalogLink}</p>
      </main>
    );
  }

  if (query.isError && !notLoggedIn) {
    return (
      <main className="p-8">
        <p>{catalogLink}</p>
        <p role="alert" className="mt-4 text-destructive">
          {query.error instanceof TooManyRequestsError
            ? TOO_MANY_REQUESTS
            : T.loadError}
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

  if (!order || notLoggedIn) {
    return (
      <main className="p-8">
        <p role="status">{T.loading}</p>
      </main>
    );
  }

  if (order.status === "PAID") {
    return (
      <main className="mx-auto max-w-md p-8">
        <h1 ref={headingRef} tabIndex={-1} className="text-2xl font-bold">
          {T.paidHeading}
        </h1>
        <p className="mt-4">{T.paidText}</p>
        <dl className="mt-4 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
          <dt className="font-semibold">{T.productTerm}</dt>
          <dd className="[overflow-wrap:anywhere]">{order.productTitle}</dd>
          <dt className="font-semibold">{T.valueTerm}</dt>
          <dd>{formatCents(order.priceCents)}</dd>
        </dl>
        <p className="mt-4">
          <Link to="/biblioteca" className="underline">
            {T.goToLibrary}
          </Link>
        </p>
        <p className="mt-4">{catalogLink}</p>
      </main>
    );
  }

  if (order.status === "EXPIRED") {
    return (
      <main className="mx-auto max-w-md p-8">
        <h1 ref={headingRef} tabIndex={-1} className="text-2xl font-bold">
          {T.expiredHeading}
        </h1>
        <p className="mt-4">{T.expiredText}</p>
        <p className="mt-4">{T.expiredAlreadyPaid}</p>
        <p className="mt-4">
          <Link to={`/catalogo/${order.productId}`} className="underline">
            {T.backToProduct}
          </Link>
        </p>
      </main>
    );
  }

  if (order.status === "REFUNDED") {
    return (
      <main className="mx-auto max-w-md p-8">
        <h1 ref={headingRef} tabIndex={-1} className="text-2xl font-bold">
          {T.refundedHeading}
        </h1>
        <p className="mt-4">{T.refundedText}</p>
        <p className="mt-4">
          <Link to={`/catalogo/${order.productId}`} className="underline">
            {T.backToProduct}
          </Link>
        </p>
      </main>
    );
  }

  const code = order.pixCopyPaste ?? "";
  return (
    <main className="mx-auto max-w-md p-8">
      <h1 ref={headingRef} tabIndex={-1} className="text-2xl font-bold">
        {T.heading}
      </h1>
      <p className="mt-4">{T.instruction}</p>
      <dl className="mt-4 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
        <dt className="font-semibold">{T.productTerm}</dt>
        <dd className="[overflow-wrap:anywhere]">{order.productTitle}</dd>
        <dt className="font-semibold">{T.valueTerm}</dt>
        <dd>{formatCents(order.priceCents)}</dd>
      </dl>
      <div className="mt-4 w-full max-w-64 rounded-md bg-white p-3">
        <QRCodeSVG
          value={code}
          role="img"
          aria-label={T.qrName}
          style={{ width: "100%", height: "auto" }}
        />
      </div>
      <div className="mt-4 flex flex-col gap-2">
        <label htmlFor="pix-code" className="font-semibold">
          {T.codeLabel}
        </label>
        <textarea
          id="pix-code"
          readOnly
          rows={5}
          value={code}
          className="w-full rounded-md border bg-background p-2 font-mono text-sm [overflow-wrap:anywhere]"
        />
        <Button type="button" onClick={() => copyCode(code)}>
          {T.copy}
        </Button>
        {copy === "done" && <p role="status">{T.copied}</p>}
        {copy === "failed" && (
          <p role="alert" className="text-destructive">
            {T.copyFailed}
          </p>
        )}
      </div>
      <p role="timer" className="mt-4 font-semibold">
        {T.countdown(mmss(remaining ?? order.secondsToExpire))}
      </p>
      <div aria-live="polite" className="sr-only">
        {announcement}
      </div>
      <p className="mt-4">{T.afterPaying}</p>
      <p className="mt-4">{catalogLink}</p>
    </main>
  );
}

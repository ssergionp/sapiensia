import { zodResolver } from "@hookform/resolvers/zod";
import { useEffect, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { Link, useNavigate, useParams } from "react-router";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { formatCents } from "@/features/products/schemas";
import { TOO_MANY_REQUESTS, TooManyRequestsError } from "@/lib/tooManyRequests";
import { useDocumentTitle } from "@/lib/useDocumentTitle";
import {
  NotLoggedInError,
  OrderNotFoundError,
  RefundDetailsTooLongError,
  RefundNotAvailableError,
  RefundReasonRequiredError,
  RefundWindowClosedError,
} from "./api";
import { useOrders, useRequestRefund } from "./hooks";
import { ORDER_TEXTS as T } from "./messages";
import { dayFromIsoDate } from "./refundDate";
import { rememberReturnTo } from "./returnTo";
import {
  type OrderList,
  REFUND_REASONS,
  type RefundForm,
  refundFormSchema,
} from "./schemas";

type OrderSummary = OrderList["items"][number];

function failureMessage(error: Error): string {
  if (error instanceof TooManyRequestsError) return TOO_MANY_REQUESTS;
  if (error instanceof RefundWindowClosedError) return T.refundWindowClosed;
  if (
    error instanceof RefundNotAvailableError ||
    error instanceof OrderNotFoundError
  ) {
    return T.refundNotAvailable;
  }
  return T.refundFailed;
}

const backLink = (
  <Link to="/pedidos" className="text-link">
    {T.backToOrders}
  </Link>
);

function RefundFields({
  order,
  onSent,
}: {
  order: OrderSummary;
  onSent: () => void;
}) {
  const navigate = useNavigate();
  const mutation = useRequestRefund();
  const [failure, setFailure] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<RefundForm>({
    resolver: zodResolver(refundFormSchema),
    defaultValues: { details: "" },
  });

  function onSubmit(values: RefundForm) {
    setFailure(null);
    const details = values.details.trim();
    mutation.mutate(
      {
        orderId: order.id,
        reason: values.reason,
        ...(details === "" ? {} : { details }),
      },
      {
        onSuccess: onSent,
        onError: (error) => {
          if (error instanceof NotLoggedInError) {
            rememberReturnTo(`/pedidos/${order.id}/reembolso`);
            navigate("/entrar");
          } else if (error instanceof RefundDetailsTooLongError) {
            setError("details", { message: T.detailsTooLong });
          } else if (error instanceof RefundReasonRequiredError) {
            setError("reason", { message: T.reasonRequired });
          } else {
            setFailure(failureMessage(error));
          }
        },
      },
    );
  }

  const reasonError = errors.reason?.message;
  const detailsError = errors.details?.message;

  return (
    <form
      onSubmit={handleSubmit(onSubmit)}
      noValidate
      className="mt-6 flex flex-col gap-6"
    >
      <fieldset
        aria-describedby={reasonError ? "reason-error" : undefined}
        className="flex flex-col gap-2"
      >
        <legend className="mb-2 font-semibold">{T.refundReasonQuestion}</legend>
        {REFUND_REASONS.map((reason) => (
          <label key={reason} className="flex items-center gap-3 py-1">
            <input
              type="radio"
              value={reason}
              aria-invalid={reasonError ? true : undefined}
              aria-describedby={reasonError ? "reason-error" : undefined}
              className="size-5 accent-primary"
              {...register("reason")}
            />
            {T.refundReasons[reason]}
          </label>
        ))}
        {reasonError && (
          <p
            id="reason-error"
            role="alert"
            className="text-sm text-destructive"
          >
            {reasonError}
          </p>
        )}
      </fieldset>
      <div className="flex flex-col gap-2">
        <Label htmlFor="refund-details">{T.refundDetailsLabel}</Label>
        <Textarea
          id="refund-details"
          aria-invalid={detailsError ? true : undefined}
          aria-describedby={
            detailsError
              ? "refund-details-hint refund-details-error"
              : "refund-details-hint"
          }
          {...register("details")}
        />
        <p id="refund-details-hint" className="text-sm text-muted-foreground">
          {T.refundDetailsHint}
        </p>
        {detailsError && (
          <p
            id="refund-details-error"
            role="alert"
            className="text-sm text-destructive"
          >
            {detailsError}
          </p>
        )}
      </div>
      {failure && (
        <p role="alert" className="text-sm text-destructive">
          {failure}
        </p>
      )}
      <Button
        type="submit"
        className="h-12 w-full text-base"
        disabled={mutation.isPending}
      >
        {mutation.isPending ? T.refundSubmitting : T.refundSubmit}
      </Button>
    </form>
  );
}

export default function RefundRequestPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const orders = useOrders();
  const heading = useRef<HTMLHeadingElement>(null);
  // The price captured at submit time: once the POST went through, the confirmation wins over every list state.
  const [sentPriceCents, setSentPriceCents] = useState<number | null>(null);
  const notLoggedIn = orders.error instanceof NotLoggedInError;

  const order = orders.data?.items.find((item) => item.id === id);
  let view: "loading" | "error" | "notFound" | "form" | "requested" | "closed";
  if (sentPriceCents !== null) view = "requested";
  else if (orders.isPending || notLoggedIn) view = "loading";
  else if (orders.isError) view = "error";
  else if (!order) view = "notFound";
  else if (order.refundRequestedAt) view = "requested";
  else if (order.refundableUntil) view = "form";
  else view = "closed";

  useDocumentTitle(
    view === "requested"
      ? T.refundRequestedDocumentTitle
      : view === "notFound"
        ? T.notFoundDocumentTitle
        : T.refundDocumentTitle,
  );

  useEffect(() => {
    if (notLoggedIn) {
      rememberReturnTo(`/pedidos/${id}/reembolso`);
      navigate("/entrar", { replace: true });
    }
  }, [notLoggedIn, id, navigate]);

  useEffect(() => {
    if (view !== "loading" && view !== "error") heading.current?.focus();
  }, [view]);

  if (view === "loading") {
    return (
      <main className="auth-panel">
        <p role="status">{T.loading}</p>
      </main>
    );
  }

  if (view === "error") {
    return (
      <main className="auth-panel">
        <p>{backLink}</p>
        <p role="alert" className="mt-4 text-destructive">
          {T.listLoadError}
        </p>
        <Button
          variant="outline"
          className="mt-2"
          onClick={() => orders.refetch()}
        >
          {T.retry}
        </Button>
      </main>
    );
  }

  const h1 = (text: string) => (
    <h1 ref={heading} tabIndex={-1} className="text-3xl outline-none">
      {text}
    </h1>
  );

  if (view === "requested") {
    const priceCents = sentPriceCents ?? order?.priceCents ?? 0;
    return (
      <main className="auth-panel">
        {h1(T.refundRequestedHeading)}
        <p className="mt-4">{T.refundRequestedText(formatCents(priceCents))}</p>
        <p className="mt-6">{backLink}</p>
      </main>
    );
  }

  if (!order) {
    return (
      <main className="auth-panel">
        {h1(T.notFoundHeading)}
        <p className="mt-4">{T.notFoundText}</p>
        <p className="mt-4">{backLink}</p>
      </main>
    );
  }

  if (view === "closed" || !order.refundableUntil) {
    return (
      <main className="auth-panel">
        {h1(T.refundHeading)}
        <p className="mt-4">{T.refundNotAvailable}</p>
        <p className="mt-4">{backLink}</p>
      </main>
    );
  }

  return (
    <main className="auth-panel">
      {h1(T.refundHeading)}
      <dl className="mt-6 grid grid-cols-[auto_1fr] items-baseline gap-x-4 gap-y-2 rounded-lg bg-muted p-4">
        <dt className="text-sm text-muted-foreground">{T.productTerm}</dt>
        <dd className="[overflow-wrap:anywhere]">{order.productTitle}</dd>
        <dt className="text-sm text-muted-foreground">{T.valueTerm}</dt>
        <dd className="text-lg font-bold tabular-nums">
          {formatCents(order.priceCents)}
        </dd>
      </dl>
      <p className="mt-4">
        {T.refundDeadlineNotice(dayFromIsoDate(order.refundableUntil))}
      </p>
      <p className="mt-2">{T.refundAccessWarning}</p>
      <RefundFields
        order={order}
        onSent={() => setSentPriceCents(order.priceCents)}
      />
      <p className="mt-6">{backLink}</p>
    </main>
  );
}

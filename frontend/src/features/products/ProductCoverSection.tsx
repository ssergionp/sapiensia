import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import CoverImage from "@/features/catalog/CoverImage";
import {
  ProductCoverRejectedError,
  ProductForbiddenError,
  ProductNotFoundError,
  ProductUnavailableError,
} from "./api";
import { useRemoveProductCover, useUploadProductCover } from "./hooks";
import {
  COVER_ERRORS,
  COVER_MESSAGES,
  PRODUCT_NOT_FOUND,
  COVER_TEXTS as T,
} from "./messages";
import {
  COVER_MEDIA_TYPES,
  formatFileSize,
  formatUploadedAt,
  MAX_COVER_BYTES,
  type Product,
} from "./schemas";

function describeError(error: unknown): string {
  if (error instanceof ProductCoverRejectedError)
    return COVER_MESSAGES[error.code];
  if (error instanceof ProductForbiddenError) return COVER_ERRORS.forbidden;
  if (error instanceof ProductNotFoundError) return PRODUCT_NOT_FOUND;
  if (error instanceof ProductUnavailableError) return COVER_ERRORS.unavailable;
  return COVER_ERRORS.unexpected;
}

const dimensions = new Intl.NumberFormat("pt-BR");

type Result = { kind: "status" | "alert"; text: string };

export default function ProductCoverSection({ product }: { product: Product }) {
  const upload = useUploadProductCover(product.id);
  const remove = useRemoveProductCover(product.id);
  const input = useRef<HTMLInputElement>(null);
  const questionRef = useRef<HTMLParagraphElement>(null);
  const removeButton = useRef<HTMLButtonElement>(null);
  const cancelled = useRef(false);
  const [result, setResult] = useState<Result | null>(null);
  const [confirming, setConfirming] = useState(false);
  const cover = product.cover;
  const busy = upload.isPending || remove.isPending;

  // The button that opened the confirmation disappears: focus goes to the question, and back on Cancelar.
  useEffect(() => {
    if (confirming) {
      questionRef.current?.focus();
    } else if (cancelled.current) {
      cancelled.current = false;
      removeButton.current?.focus();
    }
  }, [confirming]);

  function send(event: React.FormEvent) {
    event.preventDefault();
    if (busy) return;
    setResult(null);
    const chosen = input.current?.files?.[0];
    if (!chosen)
      return setResult({ kind: "alert", text: COVER_MESSAGES.COVER_REQUIRED });
    if (chosen.size > MAX_COVER_BYTES)
      return setResult({
        kind: "alert",
        text: COVER_MESSAGES.COVER_TOO_LARGE,
      });
    // An empty type (the browser could not tell) skips this check: the server decides.
    if (chosen.type !== "" && !COVER_MEDIA_TYPES.includes(chosen.type))
      return setResult({
        kind: "alert",
        text: COVER_MESSAGES.COVER_NOT_IMAGE,
      });
    upload.mutate(chosen, {
      onSuccess: () => {
        if (input.current) input.current.value = "";
        setResult({ kind: "status", text: T.sent });
      },
      onError: (e) => setResult({ kind: "alert", text: describeError(e) }),
    });
  }

  function confirmRemoval() {
    if (busy) return;
    setResult(null);
    setConfirming(false);
    remove.mutate(undefined, {
      onSuccess: () => setResult({ kind: "status", text: T.removed }),
      onError: (e) => setResult({ kind: "alert", text: describeError(e) }),
    });
  }

  const errorId = result?.kind === "alert" ? "cover-error" : undefined;

  return (
    <section
      aria-labelledby="cover-heading"
      className="rounded-xl border bg-card p-6 sm:p-8"
    >
      <h2 id="cover-heading" className="mb-4 text-xl font-bold">
        {T.title}
      </h2>
      <div className="grid gap-4">
        {cover ? (
          <div className="grid max-w-40 gap-2">
            <CoverImage
              src={`/api/admin/products/${encodeURIComponent(product.id)}/cover?v=${encodeURIComponent(cover.version)}`}
              alt={T.alt(product.title)}
            />
          </div>
        ) : null}
        <p>
          {cover
            ? T.current(
                `${dimensions.format(cover.width)} × ${dimensions.format(cover.height)} pixels`,
                formatFileSize(cover.sizeBytes),
                formatUploadedAt(cover.uploadedAt),
              )
            : T.none}
        </p>
        <form onSubmit={send} noValidate className="grid gap-4">
          <div className="grid gap-1.5">
            <Label htmlFor="product-cover">{T.label}</Label>
            <Input
              id="product-cover"
              ref={input}
              type="file"
              disabled={busy}
              accept="image/png,image/jpeg,image/webp"
              aria-invalid={errorId ? true : undefined}
              aria-describedby={["cover-help", "cover-help-replace", errorId]
                .filter(Boolean)
                .join(" ")}
            />
            <p id="cover-help" className="text-sm text-muted-foreground">
              {T.help}
            </p>
            <p
              id="cover-help-replace"
              className="text-sm text-muted-foreground"
            >
              {T.helpReplace}
            </p>
            {result?.kind === "alert" && (
              <p
                id="cover-error"
                role="alert"
                className="text-sm text-destructive"
              >
                {result.text}
              </p>
            )}
          </div>
          {result?.kind === "status" && (
            <p role="status" className="text-sm">
              {result.text}
            </p>
          )}
          <div className="flex flex-wrap gap-2">
            <Button type="submit" disabled={busy}>
              {upload.isPending ? T.sending : T.send}
            </Button>
            {cover && !confirming && (
              <Button
                ref={removeButton}
                type="button"
                variant="outline"
                disabled={busy}
                onClick={() => setConfirming(true)}
              >
                {remove.isPending ? T.removing : T.remove}
              </Button>
            )}
          </div>
        </form>
        {confirming && (
          <div className="grid gap-4">
            <p ref={questionRef} tabIndex={-1}>
              {T.confirmQuestion}
            </p>
            <div className="flex gap-4">
              <Button type="button" onClick={confirmRemoval}>
                {T.confirmRemove}
              </Button>
              <Button
                type="button"
                variant="outline"
                onClick={() => {
                  cancelled.current = true;
                  setConfirming(false);
                }}
              >
                {T.cancel}
              </Button>
            </div>
          </div>
        )}
      </div>
    </section>
  );
}

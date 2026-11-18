import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  ProductForbiddenError,
  ProductNotFoundError,
  ProductUnavailableError,
  ProductValidationError,
} from "./api";
import { usePublishProduct, useUnpublishProduct } from "./hooks";
import {
  PRODUCT_NOT_FOUND,
  PUBLICATION_ERRORS,
  PUBLICATION_MESSAGES as T,
} from "./messages";
import type { Product } from "./schemas";

type Result = { kind: "status" | "alert"; text: string };

function describeError(error: unknown): string {
  if (
    error instanceof ProductValidationError &&
    error.errors.file === "FILE_REQUIRED"
  )
    return PUBLICATION_ERRORS.fileRequired;
  if (error instanceof ProductForbiddenError)
    return PUBLICATION_ERRORS.forbidden;
  if (error instanceof ProductNotFoundError) return PRODUCT_NOT_FOUND;
  if (error instanceof ProductUnavailableError)
    return PUBLICATION_ERRORS.unavailable;
  return PUBLICATION_ERRORS.unexpected;
}

export default function ProductPublicationSection({
  product,
}: {
  product: Product;
}) {
  const publish = usePublishProduct(product.id);
  const unpublish = useUnpublishProduct(product.id);
  const [confirming, setConfirming] = useState(false);
  const [result, setResult] = useState<Result | null>(null);
  const resultRef = useRef<HTMLParagraphElement>(null);
  const questionRef = useRef<HTMLParagraphElement>(null);
  const publishButtonRef = useRef<HTMLButtonElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const cancelled = useRef(false);
  const pending = publish.isPending || unpublish.isPending;
  const hasFile = product.file !== null;

  // The result message takes the focus, so a screen reader user hears what happened.
  useEffect(() => {
    if (result) resultRef.current?.focus();
  }, [result]);

  // The button that was focused disappears when the confirmation opens: focus goes to the question (not to
  // Confirmar publicação, so a double Enter cannot publish) and, on Cancelar, back to Publicar.
  useEffect(() => {
    if (confirming) {
      questionRef.current?.focus();
    } else if (cancelled.current) {
      cancelled.current = false;
      publishButtonRef.current?.focus();
    }
  }, [confirming]);

  function run(action: "publish" | "unpublish") {
    if (pending) return;
    setResult(null);
    setConfirming(false);
    // The clicked button unmounts or gets disabled: keep the focus in the section until the answer arrives.
    headingRef.current?.focus();
    const mutation = action === "publish" ? publish : unpublish;
    mutation.mutate(undefined, {
      onSuccess: () =>
        setResult({
          kind: "status",
          text: action === "publish" ? T.published : T.unpublished,
        }),
      onError: (e) => setResult({ kind: "alert", text: describeError(e) }),
    });
  }

  const publishLabel = publish.isPending
    ? T.publishing
    : product.status === "UNPUBLISHED"
      ? T.publishAgain
      : T.publish;

  return (
    <section
      aria-labelledby="publication-heading"
      className="rounded-xl border bg-card p-6 sm:p-8"
    >
      <h2
        id="publication-heading"
        ref={headingRef}
        tabIndex={-1}
        className="mb-4 text-xl font-bold"
      >
        {T.heading}
      </h2>
      <div className="grid gap-4">
        {product.status === "PUBLISHED" ? (
          <>
            <p className="text-sm text-muted-foreground">{T.keepsAccess}</p>
            <div>
              <Button
                type="button"
                disabled={pending}
                onClick={() => run("unpublish")}
              >
                {unpublish.isPending ? T.unpublishing : T.unpublish}
              </Button>
            </div>
          </>
        ) : confirming ? (
          <>
            <p ref={questionRef} tabIndex={-1}>
              {T.confirmQuestion}
            </p>
            <div className="flex gap-4">
              <Button type="button" onClick={() => run("publish")}>
                {T.confirm}
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
          </>
        ) : (
          <>
            {!hasFile && (
              <p className="text-sm text-muted-foreground">{T.needFile}</p>
            )}
            <div>
              <Button
                ref={publishButtonRef}
                type="button"
                disabled={!hasFile || pending}
                onClick={() =>
                  product.status === "DRAFT"
                    ? setConfirming(true)
                    : run("publish")
                }
              >
                {publishLabel}
              </Button>
            </div>
          </>
        )}
        {result && (
          <p
            ref={resultRef}
            tabIndex={-1}
            role={result.kind}
            className={
              result.kind === "alert" ? "text-sm text-destructive" : "text-sm"
            }
          >
            {result.text}
          </p>
        )}
      </div>
    </section>
  );
}

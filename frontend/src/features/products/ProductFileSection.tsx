import { useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  ProductFileRejectedError,
  ProductForbiddenError,
  ProductNotFoundError,
  ProductUnavailableError,
} from "./api";
import { useUploadProductFile } from "./hooks";
import {
  FILE_MESSAGES,
  FILE_UPLOAD_ERRORS,
  PRODUCT_NOT_FOUND,
} from "./messages";
import {
  formatFileSize,
  formatUploadedAt,
  MAX_FILE_BYTES,
  type Product,
} from "./schemas";

function describeUploadError(error: unknown): string {
  if (error instanceof ProductFileRejectedError)
    return FILE_MESSAGES[error.code];
  if (error instanceof ProductForbiddenError)
    return FILE_UPLOAD_ERRORS.forbidden;
  if (error instanceof ProductNotFoundError) return PRODUCT_NOT_FOUND;
  if (error instanceof ProductUnavailableError)
    return FILE_UPLOAD_ERRORS.unavailable;
  return FILE_UPLOAD_ERRORS.unexpected;
}

export default function ProductFileSection({ product }: { product: Product }) {
  const upload = useUploadProductFile(product.id);
  const input = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);
  const file = product.file;

  function send(event: React.FormEvent) {
    event.preventDefault();
    if (upload.isPending) return;
    upload.reset();
    setError(null);
    const chosen = input.current?.files?.[0];
    if (!chosen) return setError(FILE_MESSAGES.FILE_REQUIRED);
    if (!chosen.name.toLowerCase().endsWith(".pdf"))
      return setError(FILE_MESSAGES.FILE_NOT_PDF);
    if (chosen.size > MAX_FILE_BYTES)
      return setError(FILE_MESSAGES.FILE_TOO_LARGE);
    upload.mutate(chosen, {
      onSuccess: () => {
        if (input.current) input.current.value = "";
      },
      onError: (e) => setError(describeUploadError(e)),
    });
  }

  const currentFile = (
    <p>
      {file
        ? `Arquivo atual: ${file.pageCount} ${file.pageCount === 1 ? "página" : "páginas"}, ${formatFileSize(file.sizeBytes)}, enviado em ${formatUploadedAt(file.uploadedAt)}.`
        : "Nenhum arquivo enviado."}
    </p>
  );

  return (
    <section aria-labelledby="file-heading">
      <h2 id="file-heading" className="mb-4 text-lg font-semibold">
        Arquivo do produto
      </h2>
      {product.firstPublishedAt !== null ? (
        <div className="grid gap-4">
          {currentFile}
          <p>{FILE_MESSAGES.PUBLISHED_BEFORE}</p>
        </div>
      ) : (
        <form onSubmit={send} noValidate className="grid gap-4">
          {currentFile}
          <div className="grid gap-1.5">
            <Label htmlFor="product-file">Arquivo PDF</Label>
            <Input
              id="product-file"
              ref={input}
              type="file"
              accept="application/pdf,.pdf"
              aria-invalid={error ? true : undefined}
              aria-describedby={[
                "file-help",
                file ? "file-help-replace" : null,
                error ? "file-error" : null,
              ]
                .filter(Boolean)
                .join(" ")}
            />
            <p id="file-help" className="text-sm text-muted-foreground">
              Somente PDF, até 50 MB e 1.000 páginas.
            </p>
            {file && (
              <p
                id="file-help-replace"
                className="text-sm text-muted-foreground"
              >
                Enviar outro arquivo substitui o atual.
              </p>
            )}
            {error && (
              <p
                id="file-error"
                role="alert"
                className="text-sm text-destructive"
              >
                {error}
              </p>
            )}
          </div>
          {upload.isSuccess && (
            <p role="status" className="text-sm">
              Arquivo enviado.
            </p>
          )}
          <div>
            <Button type="submit" disabled={upload.isPending}>
              {upload.isPending ? "Enviando arquivo…" : "Enviar arquivo"}
            </Button>
          </div>
        </form>
      )}
    </section>
  );
}

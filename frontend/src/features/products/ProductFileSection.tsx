import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  ReauthenticationLockedError,
  ReauthenticationRejectedError,
  ReauthenticationUnavailableError,
} from "@/features/admin/api";
import { useReauthenticate } from "@/features/admin/hooks";
import {
  ProductFileReauthenticationRequiredError,
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
  REAUTHENTICATION_TEXTS,
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

function describeReauthenticationError(error: unknown): string {
  if (error instanceof ReauthenticationRejectedError)
    return REAUTHENTICATION_TEXTS.rejected;
  if (error instanceof ReauthenticationLockedError)
    return REAUTHENTICATION_TEXTS.locked;
  if (error instanceof ReauthenticationUnavailableError)
    return FILE_UPLOAD_ERRORS.unavailable;
  return FILE_UPLOAD_ERRORS.unexpected;
}

export default function ProductFileSection({ product }: { product: Product }) {
  const upload = useUploadProductFile(product.id);
  const reauthenticate = useReauthenticate();
  const input = useRef<HTMLInputElement>(null);
  const sendButton = useRef<HTMLButtonElement>(null);
  const passwordField = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);
  // The chosen file lives here while the identity form is open, so it is sent as is after the confirmation.
  const [pending, setPending] = useState<File | null>(null);
  const [asking, setAsking] = useState(false);
  const [askError, setAskError] = useState<string | null>(null);
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  // Bumped each time the form (re)opens so the focus goes back to the password.
  const [focusTick, setFocusTick] = useState(0);
  const file = product.file;
  const published = product.firstPublishedAt !== null;
  const busy = upload.isPending || reauthenticate.isPending;

  // biome-ignore lint/correctness/useExhaustiveDependencies: focusTick is the trigger.
  useEffect(() => {
    if (asking) passwordField.current?.focus();
  }, [asking, focusTick]);

  function openForm(chosen: File, message: string | null) {
    setPending(chosen);
    setAskError(message);
    setAsking(true);
    setFocusTick((tick) => tick + 1);
  }

  function sendFile(chosen: File) {
    upload.mutate(chosen, {
      onSuccess: () => {
        if (input.current) input.current.value = "";
        setAsking(false);
        setPending(null);
        setAskError(null);
      },
      onError: (e) => {
        if (e instanceof ProductFileReauthenticationRequiredError) {
          openForm(chosen, REAUTHENTICATION_TEXTS.expired);
          return;
        }
        setAsking(false);
        setError(describeUploadError(e));
      },
    });
  }

  function send(event: React.FormEvent) {
    event.preventDefault();
    if (busy) return;
    upload.reset();
    setError(null);
    const chosen = input.current?.files?.[0];
    if (!chosen) return setError(FILE_MESSAGES.FILE_REQUIRED);
    if (!chosen.name.toLowerCase().endsWith(".pdf"))
      return setError(FILE_MESSAGES.FILE_NOT_PDF);
    if (chosen.size > MAX_FILE_BYTES)
      return setError(FILE_MESSAGES.FILE_TOO_LARGE);
    if (published) return openForm(chosen, null);
    sendFile(chosen);
  }

  function confirm(event: React.FormEvent) {
    event.preventDefault();
    if (busy || !pending) return;
    const credentials = { password, code };
    const chosen = pending;
    // The password and the code never outlive the attempt.
    setPassword("");
    setCode("");
    setAskError(null);
    reauthenticate.mutate(credentials, {
      onSuccess: () => sendFile(chosen),
      onError: (e) => {
        setAskError(describeReauthenticationError(e));
        // The button was disabled meanwhile, so the focus fell to the body.
        setFocusTick((tick) => tick + 1);
      },
    });
  }

  function closeForm() {
    setAsking(false);
    setPending(null);
    setAskError(null);
    setPassword("");
    setCode("");
  }

  function cancel() {
    closeForm();
    sendButton.current?.focus();
  }

  // A different file must go through the identity form again; the old one is never sent.
  function changeFile() {
    if (asking) closeForm();
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
      <div className="grid gap-4">
        <form onSubmit={send} noValidate className="grid gap-4">
          {currentFile}
          <div className="grid gap-1.5">
            <Label htmlFor="product-file">Arquivo PDF</Label>
            <Input
              id="product-file"
              ref={input}
              type="file"
              onChange={changeFile}
              disabled={busy}
              accept="application/pdf,.pdf"
              aria-invalid={error ? true : undefined}
              aria-describedby={[
                "file-help",
                file ? "file-help-replace" : null,
                published ? "file-help-published" : null,
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
            {published && (
              <p
                id="file-help-published"
                className="text-sm text-muted-foreground"
              >
                {REAUTHENTICATION_TEXTS.publishedNotice}
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
            <Button ref={sendButton} type="submit" disabled={busy}>
              {upload.isPending ? "Enviando arquivo…" : "Enviar arquivo"}
            </Button>
          </div>
        </form>
        {asking && (
          <form
            onSubmit={confirm}
            noValidate
            aria-labelledby="reauthentication-heading"
            className="grid gap-4"
          >
            <h3 id="reauthentication-heading" className="font-semibold">
              {REAUTHENTICATION_TEXTS.title}
            </h3>
            <div className="grid gap-1.5">
              <Label htmlFor="reauthentication-password">
                {REAUTHENTICATION_TEXTS.passwordLabel}
              </Label>
              <Input
                id="reauthentication-password"
                ref={passwordField}
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="reauthentication-code">
                {REAUTHENTICATION_TEXTS.codeLabel}
              </Label>
              <Input
                id="reauthentication-code"
                inputMode="numeric"
                autoComplete="one-time-code"
                value={code}
                onChange={(event) => setCode(event.target.value)}
              />
            </div>
            {askError && (
              <p role="alert" className="text-sm text-destructive">
                {askError}
              </p>
            )}
            <div className="flex gap-2">
              <Button type="submit" disabled={busy}>
                {busy
                  ? REAUTHENTICATION_TEXTS.confirming
                  : REAUTHENTICATION_TEXTS.confirm}
              </Button>
              <Button
                type="button"
                variant="outline"
                disabled={busy}
                onClick={cancel}
              >
                {REAUTHENTICATION_TEXTS.cancel}
              </Button>
            </div>
          </form>
        )}
      </div>
    </section>
  );
}

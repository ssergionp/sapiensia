import { zodResolver } from "@hookform/resolvers/zod";
import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { useNavigate } from "react-router";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { TOO_MANY_REQUESTS, TooManyRequestsError } from "@/lib/tooManyRequests";
import {
  AlreadyOwnedError,
  CpfInvalidError,
  CpfRequiredError,
  NotLoggedInError,
  OrderInProgressError,
  PaymentUnavailableError,
  ProductUnavailableError,
} from "./api";
import { normalizeCpf } from "./cpf";
import { useCreateOrder } from "./hooks";
import { ORDER_TEXTS as T } from "./messages";
import { rememberReturnTo } from "./returnTo";
import { type CpfForm, cpfFormSchema } from "./schemas";

function failureMessage(error: Error): string {
  if (error instanceof TooManyRequestsError) return TOO_MANY_REQUESTS;
  if (error instanceof PaymentUnavailableError) return T.paymentUnavailable;
  if (error instanceof OrderInProgressError) return T.inProgress;
  if (error instanceof ProductUnavailableError) return T.productUnavailable;
  if (error instanceof AlreadyOwnedError) return T.alreadyOwned;
  return T.unexpected;
}

export default function BuyWithPix({ productId }: { productId: string }) {
  const navigate = useNavigate();
  const mutation = useCreateOrder();
  const [cpfOpen, setCpfOpen] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    reset,
    formState: { errors },
  } = useForm<CpfForm>({
    resolver: zodResolver(cpfFormSchema),
    defaultValues: { cpf: "" },
  });

  useEffect(() => {
    if (cpfOpen) setFocus("cpf");
  }, [cpfOpen, setFocus]);

  // The first call goes without the CPF; the block opens only if the server asks for it.
  function send(cpf?: string) {
    setFailure(null);
    mutation.mutate(cpf === undefined ? { productId } : { productId, cpf }, {
      onSuccess: (order) => navigate(`/pedidos/${order.id}`),
      onError: (error) => {
        if (error instanceof NotLoggedInError) {
          rememberReturnTo(`/catalogo/${productId}`);
          navigate("/entrar");
        } else if (error instanceof CpfRequiredError && cpf === undefined) {
          setCpfOpen(true);
        } else if (
          error instanceof CpfInvalidError ||
          error instanceof CpfRequiredError
        ) {
          setError("cpf", { message: T.cpfInvalid });
        } else {
          setFailure(failureMessage(error));
        }
      },
    });
  }

  function onSubmit(values: CpfForm) {
    const cpf = normalizeCpf(values.cpf);
    // The CPF lives only in the form state: clear it as soon as it is on its way.
    reset({ cpf: "" });
    send(cpf);
  }

  const cpfError = errors.cpf?.message;

  return (
    <div className="mt-6">
      {cpfOpen ? (
        <form
          onSubmit={handleSubmit(onSubmit)}
          noValidate
          className="flex max-w-md flex-col gap-4"
        >
          <h2 className="text-xl font-semibold">{T.cpfHeading}</h2>
          <p className="text-sm">{T.cpfExplanation}</p>
          <div className="flex flex-col gap-2">
            <Label htmlFor="cpf">{T.cpfLabel}</Label>
            <Input
              id="cpf"
              inputMode="numeric"
              autoComplete="off"
              aria-invalid={cpfError ? true : undefined}
              aria-describedby={cpfError ? "cpf-hint cpf-error" : "cpf-hint"}
              {...register("cpf")}
            />
            <p id="cpf-hint" className="text-sm text-muted-foreground">
              {T.cpfHint}
            </p>
            {cpfError && (
              <p
                id="cpf-error"
                role="alert"
                className="text-sm text-destructive"
              >
                {cpfError}
              </p>
            )}
          </div>
          {failure && (
            <p role="alert" className="text-sm text-destructive">
              {failure}
            </p>
          )}
          <Button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? T.buying : T.generate}
          </Button>
        </form>
      ) : (
        <>
          <Button
            type="button"
            disabled={mutation.isPending}
            onClick={() => send()}
          >
            {mutation.isPending ? T.buying : T.buy}
          </Button>
          {failure && (
            <p role="alert" className="mt-2 text-sm text-destructive">
              {failure}
            </p>
          )}
        </>
      )}
    </div>
  );
}

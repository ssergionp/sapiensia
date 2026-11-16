import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { Link } from "react-router";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { TOO_MANY_REQUESTS, TooManyRequestsError } from "@/lib/tooManyRequests";
import { ConfirmEmailError } from "./api";
import { useConfirmEmail } from "./hooks";
import {
  type ConfirmEmailInput,
  confirmEmailMessages,
  confirmEmailSchema,
} from "./schemas";

const INVALID_LINK =
  "Este link venceu ou já foi usado. Se o seu e-mail ainda não foi confirmado, faça o cadastro de novo para receber outro link.";
const UNEXPECTED_ERROR =
  "Não foi possível confirmar agora. Tente novamente em instantes.";

export default function ConfirmEmailPage() {
  // The token travels in the URL fragment so it is never sent to servers or logs.
  const [token] = useState(() => window.location.hash.replace(/^#/, ""));
  const [tokenRejected, setTokenRejected] = useState(false);
  const [tokenLocked, setTokenLocked] = useState(false);
  const [showUnexpected, setShowUnexpected] = useState(false);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<ConfirmEmailInput>({
    resolver: zodResolver(confirmEmailSchema),
    defaultValues: { password: "" },
  });
  const mutation = useConfirmEmail();

  function onSubmit(values: ConfirmEmailInput) {
    setShowUnexpected(false);
    mutation.mutate(
      { token, password: values.password },
      {
        onError: (error) => {
          if (error instanceof ConfirmEmailError) {
            if (error.fieldErrors.token) {
              if (error.fieldErrors.token === "TOKEN_LOCKED") {
                setTokenLocked(true);
              } else {
                setTokenRejected(true);
              }
              return;
            }
            const code = error.fieldErrors.password;
            if (code) {
              setError("password", {
                message: confirmEmailMessages[code] ?? UNEXPECTED_ERROR,
              });
              return;
            }
          }
          setShowUnexpected(true);
        },
      },
    );
  }

  let content: React.ReactNode;
  if (mutation.isSuccess) {
    content = (
      <p role="status" className="mt-4">
        E-mail confirmado. Agora você já pode entrar na sua conta.
      </p>
    );
  } else if (tokenLocked) {
    content = (
      <p role="alert" className="mt-4">
        Você errou a senha 5 vezes e, por segurança, este link foi cancelado.{" "}
        <Link to="/cadastro" className="underline">
          Faça o cadastro de novo
        </Link>{" "}
        para receber outro link.
      </p>
    );
  } else if (!token || tokenRejected) {
    content = (
      <p role="alert" className="mt-4">
        {INVALID_LINK}
      </p>
    );
  } else {
    content = (
      <>
        <p className="mt-2 text-muted-foreground">
          Digite a senha que você escolheu no cadastro para confirmar seu
          e-mail.
        </p>
        <form
          onSubmit={handleSubmit(onSubmit)}
          noValidate
          className="mt-6 flex flex-col gap-4"
        >
          <div className="flex flex-col gap-2">
            <Label htmlFor="password">Senha</Label>
            <Input
              id="password"
              type="password"
              autoComplete="current-password"
              aria-invalid={errors.password ? true : undefined}
              aria-describedby={errors.password ? "password-error" : undefined}
              {...register("password")}
            />
            {errors.password && (
              <p
                id="password-error"
                role="alert"
                className="text-sm text-destructive"
              >
                {errors.password.message}
              </p>
            )}
          </div>
          {showUnexpected && (
            <p role="alert" className="text-sm text-destructive">
              {mutation.error instanceof TooManyRequestsError
                ? TOO_MANY_REQUESTS
                : UNEXPECTED_ERROR}
            </p>
          )}
          <Button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? "Confirmando…" : "Confirmar e-mail"}
          </Button>
        </form>
      </>
    );
  }

  return (
    <main className="mx-auto max-w-md p-8">
      <h1 className="text-2xl font-bold">Confirmação de e-mail</h1>
      {content}
    </main>
  );
}

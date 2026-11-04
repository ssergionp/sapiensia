import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { Link, useLocation } from "react-router";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ResetPasswordError } from "./api";
import { useResetPassword } from "./hooks";
import {
  errorMessages,
  type ResetPasswordInput,
  resetPasswordSchema,
} from "./schemas";

const UNEXPECTED_ERROR =
  "Não foi possível salvar agora. Tente novamente em instantes.";

export default function ResetPasswordPage() {
  // The token travels in the URL fragment so it is never sent to servers or logs.
  // Read from the router so a newer link opened in the same tab replaces it; the key
  // resets the form and its flags for the new token.
  const token = useLocation().hash.replace(/^#/, "");
  return <ResetPasswordContent key={token} token={token} />;
}

function ResetPasswordContent({ token }: { token: string }) {
  const [tokenRejected, setTokenRejected] = useState(false);
  const [showUnexpected, setShowUnexpected] = useState(false);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<ResetPasswordInput>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { newPassword: "" },
  });
  const mutation = useResetPassword();

  function onSubmit(values: ResetPasswordInput) {
    setShowUnexpected(false);
    mutation.mutate(
      { token, newPassword: values.newPassword },
      {
        onError: (error) => {
          if (error instanceof ResetPasswordError) {
            if (error.fieldErrors.token) {
              setTokenRejected(true);
              return;
            }
            const message = errorMessages[error.fieldErrors.newPassword];
            if (message) {
              setError("newPassword", { message });
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
        Senha alterada. Por segurança, você saiu de todos os aparelhos. Entre
        com a nova senha.{" "}
        <Link to="/entrar" className="underline">
          Entrar
        </Link>
      </p>
    );
  } else if (!token || tokenRejected) {
    content = (
      <p role="alert" className="mt-4">
        Este link venceu ou já foi usado. Peça um novo link em "
        <Link to="/esqueci-senha" className="underline">
          Esqueci minha senha
        </Link>
        ".
      </p>
    );
  } else {
    content = (
      <>
        <p className="mt-2 text-muted-foreground">
          Escolha a nova senha da sua conta.
        </p>
        <form
          onSubmit={handleSubmit(onSubmit)}
          noValidate
          className="mt-6 flex flex-col gap-4"
        >
          <div className="flex flex-col gap-2">
            <Label htmlFor="newPassword">Nova senha</Label>
            <Input
              id="newPassword"
              type="password"
              autoComplete="new-password"
              aria-invalid={errors.newPassword ? true : undefined}
              aria-describedby={
                errors.newPassword ? "newPassword-error" : undefined
              }
              {...register("newPassword")}
            />
            {errors.newPassword && (
              <p
                id="newPassword-error"
                role="alert"
                className="text-sm text-destructive"
              >
                {errors.newPassword.message}
              </p>
            )}
          </div>
          {showUnexpected && (
            <p role="alert" className="text-sm text-destructive">
              {UNEXPECTED_ERROR}
            </p>
          )}
          <Button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? "Salvando…" : "Salvar nova senha"}
          </Button>
        </form>
      </>
    );
  }

  return (
    <main className="mx-auto max-w-md p-8">
      <h1 className="text-2xl font-bold">Nova senha</h1>
      {content}
    </main>
  );
}

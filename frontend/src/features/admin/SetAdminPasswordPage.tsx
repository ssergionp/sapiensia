import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { Link, useLocation } from "react-router";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ResetPasswordError } from "@/features/customer/api";
import {
  errorMessages,
  type ResetPasswordInput,
  resetPasswordSchema,
  SAVE_UNEXPECTED_ERROR,
} from "@/features/customer/schemas";
import { useSetAdminPassword } from "./hooks";

export default function SetAdminPasswordPage() {
  // The token travels in the URL fragment so it is never sent to servers or logs; the key resets the
  // form when a newer link replaces it in the same tab.
  const token = useLocation().hash.replace(/^#/, "");
  return <SetAdminPasswordContent key={token} token={token} />;
}

function SetAdminPasswordContent({ token }: { token: string }) {
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
  const mutation = useSetAdminPassword();

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
        Senha definida. Entre no painel com a nova senha.{" "}
        <Link to="/painel/entrar" className="underline">
          Entrar no painel
        </Link>
      </p>
    );
  } else if (!token || tokenRejected) {
    content = (
      <p role="alert" className="mt-4">
        Este link venceu ou já foi usado. Se você ainda não definiu a senha, um
        novo link chega por e-mail quando o sistema for reiniciado.
      </p>
    );
  } else {
    content = (
      <>
        <p className="mt-2 text-muted-foreground">
          Escolha a senha da sua conta de Administrador.
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
              {SAVE_UNEXPECTED_ERROR}
            </p>
          )}
          <Button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? "Salvando…" : "Salvar senha"}
          </Button>
        </form>
      </>
    );
  }

  return (
    <main className="mx-auto max-w-md p-8">
      <h1 className="text-2xl font-bold">Definir senha do painel</h1>
      {content}
    </main>
  );
}

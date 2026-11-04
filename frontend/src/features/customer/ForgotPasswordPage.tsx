import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useRequestPasswordReset } from "./hooks";
import { type ForgotPasswordInput, forgotPasswordSchema } from "./schemas";

const SUCCESS =
  "Pedido recebido. Se o e-mail estiver cadastrado, você receberá um link em instantes.";
const UNEXPECTED_ERROR =
  "Não foi possível enviar agora. Tente novamente em instantes.";

export default function ForgotPasswordPage() {
  const [showUnexpected, setShowUnexpected] = useState(false);
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ForgotPasswordInput>({
    resolver: zodResolver(forgotPasswordSchema),
    defaultValues: { email: "" },
  });
  const mutation = useRequestPasswordReset();

  function onSubmit(values: ForgotPasswordInput) {
    setShowUnexpected(false);
    mutation.mutate(values, { onError: () => setShowUnexpected(true) });
  }

  return (
    <main className="mx-auto max-w-md p-8">
      <h1 className="text-2xl font-bold">Recuperar senha</h1>
      {mutation.isSuccess ? (
        <p role="status" className="mt-4">
          {SUCCESS}
        </p>
      ) : (
        <>
          <p className="mt-2 text-muted-foreground">
            Informe o e-mail da sua conta. Se ele estiver cadastrado, enviaremos
            um link para você criar uma nova senha.
          </p>
          <form
            onSubmit={handleSubmit(onSubmit)}
            noValidate
            className="mt-6 flex flex-col gap-4"
          >
            <div className="flex flex-col gap-2">
              <Label htmlFor="email">E-mail</Label>
              <Input
                id="email"
                type="email"
                autoComplete="email"
                aria-invalid={errors.email ? true : undefined}
                aria-describedby={errors.email ? "email-error" : undefined}
                {...register("email")}
              />
              {errors.email && (
                <p
                  id="email-error"
                  role="alert"
                  className="text-sm text-destructive"
                >
                  {errors.email.message}
                </p>
              )}
            </div>
            {showUnexpected && (
              <p role="alert" className="text-sm text-destructive">
                {UNEXPECTED_ERROR}
              </p>
            )}
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? "Enviando…" : "Enviar link"}
            </Button>
          </form>
        </>
      )}
    </main>
  );
}

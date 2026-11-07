import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useNavigate } from "react-router";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LoginBlockedError, LoginRejectedError } from "@/features/session/api";
import {
  LOGIN_BLOCKED,
  LOGIN_UNEXPECTED_ERROR,
} from "@/features/session/messages";
import { type LoginInput, loginSchema } from "@/features/session/schemas";
import { useAdminLogin } from "./hooks";

const ADMIN_LOGIN_FAILED = "E-mail ou senha incorretos.";

function failureMessage(error: Error): string {
  if (error instanceof LoginRejectedError) return ADMIN_LOGIN_FAILED;
  if (error instanceof LoginBlockedError) return LOGIN_BLOCKED;
  return LOGIN_UNEXPECTED_ERROR;
}

export default function AdminLoginPage() {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginInput>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: "", password: "" },
  });
  const mutation = useAdminLogin();
  const navigate = useNavigate();
  const [failure, setFailure] = useState<string | null>(null);

  function onSubmit(values: LoginInput) {
    setFailure(null);
    mutation.mutate(values, {
      onSuccess: () => navigate("/painel/verificacao"),
      onError: (error) => setFailure(failureMessage(error)),
    });
  }

  return (
    <main className="mx-auto max-w-md p-8">
      <h1 className="text-2xl font-bold">Entrar no painel</h1>
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
            autoComplete="username"
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
        {failure && (
          <p role="alert" className="text-sm text-destructive">
            {failure}
          </p>
        )}
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? "Entrando…" : "Entrar"}
        </Button>
      </form>
    </main>
  );
}

import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { Link, useNavigate } from "react-router";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { hasReturnTo, takeReturnTo } from "../orders/returnTo";
import { LoginBlockedError, LoginRejectedError } from "./api";
import { useLogin } from "./hooks";
import {
  LOGIN_BLOCKED,
  LOGIN_PURCHASE_NOTICE,
  LOGIN_UNEXPECTED_ERROR,
} from "./messages";
import { type LoginInput, loginSchema } from "./schemas";

const LOGIN_FAILED =
  "E-mail ou senha incorretos, ou o e-mail ainda não foi confirmado.";

function failureMessage(error: Error): string {
  if (error instanceof LoginRejectedError) return LOGIN_FAILED;
  if (error instanceof LoginBlockedError) return LOGIN_BLOCKED;
  return LOGIN_UNEXPECTED_ERROR;
}

export default function LoginPage() {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginInput>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: "", password: "" },
  });
  const mutation = useLogin();
  const navigate = useNavigate();
  const [failure, setFailure] = useState<string | null>(null);
  const [purchaseWaiting] = useState(() => hasReturnTo());

  function onSubmit(values: LoginInput) {
    setFailure(null);
    mutation.mutate(values, {
      onSuccess: () => navigate(takeReturnTo() ?? "/"),
      onError: (error) => setFailure(failureMessage(error)),
    });
  }

  return (
    <main className="auth-panel">
      <h1 className="text-3xl">Entrar</h1>
      {purchaseWaiting && (
        <p role="status" className="mt-4 rounded-lg bg-muted p-4 text-sm">
          {LOGIN_PURCHASE_NOTICE}
        </p>
      )}
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
          <Link
            to="/esqueci-senha"
            className="w-fit text-sm text-muted-foreground underline underline-offset-4"
          >
            Esqueci minha senha
          </Link>
        </div>
        {failure && (
          <p role="alert" className="text-sm text-destructive">
            {failure}
          </p>
        )}
        <Button
          type="submit"
          className="mt-2 h-11 w-full text-base"
          disabled={mutation.isPending}
        >
          {mutation.isPending ? "Entrando…" : "Entrar"}
        </Button>
      </form>
      <div className="mt-6 border-t pt-6">
        <Link
          to="/cadastro"
          className={buttonVariants({
            variant: "outline",
            className: "h-11 w-full text-base",
          })}
        >
          Ainda não tem conta? Criar conta
        </Link>
      </div>
    </main>
  );
}

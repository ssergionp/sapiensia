import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { RegisterCustomerError } from "./api";
import { useRegisterCustomer } from "./hooks";
import {
  errorMessages,
  type RegisterCustomerInput,
  registerCustomerSchema,
} from "./schemas";

const UNEXPECTED_ERROR =
  "Não foi possível criar a conta agora. Tente novamente em instantes.";

export default function RegisterPage() {
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<RegisterCustomerInput>({
    resolver: zodResolver(registerCustomerSchema),
    defaultValues: { email: "", password: "" },
  });
  const mutation = useRegisterCustomer();

  function onSubmit(values: RegisterCustomerInput) {
    mutation.mutate(values, {
      onError: (error) => {
        if (!(error instanceof RegisterCustomerError)) return;
        for (const field of ["email", "password"] as const) {
          const code = error.fieldErrors[field];
          if (code) {
            setError(field, {
              message: errorMessages[code] ?? UNEXPECTED_ERROR,
            });
          }
        }
      },
    });
  }

  if (mutation.isSuccess) {
    return (
      <main className="mx-auto max-w-md p-8">
        <h1 className="text-2xl font-bold">Criar conta</h1>
        <p role="status" className="mt-4">
          Cadastro recebido. Verifique seu e-mail para continuar.
        </p>
      </main>
    );
  }

  const hasFieldError = Boolean(errors.email || errors.password);
  const showUnexpected =
    mutation.isError &&
    (!(mutation.error instanceof RegisterCustomerError) || !hasFieldError);

  return (
    <main className="mx-auto max-w-md p-8">
      <h1 className="text-2xl font-bold">Criar conta</h1>
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
            autoComplete="new-password"
            aria-invalid={errors.password ? true : undefined}
            aria-describedby={
              errors.password ? "password-hint password-error" : "password-hint"
            }
            {...register("password")}
          />
          <p id="password-hint" className="text-sm text-muted-foreground">
            Use pelo menos 12 caracteres.
          </p>
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
            {UNEXPECTED_ERROR}
          </p>
        )}
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? "Criando conta…" : "Criar conta"}
        </Button>
      </form>
    </main>
  );
}

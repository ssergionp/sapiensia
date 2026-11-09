import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LoginBlockedError } from "@/features/session/api";
import { LOGIN_BLOCKED } from "@/features/session/messages";
import { MfaCodeRejectedError } from "./api";
import { useRegenerateRecoveryCodes } from "./hooks";
import RecoveryCodesList from "./RecoveryCodesList";
import {
  type MfaCodeInput,
  type MfaCodeOutput,
  mfaCodeSchema,
} from "./schemas";

const TITLE = "Códigos de recuperação";
const EXPLANATION =
  "Gere novos códigos se você perdeu os anteriores ou já usou alguns. Os códigos antigos param de funcionar na hora.";
const START = "Gerar novos códigos";
const INSTRUCTION =
  "Para confirmar, digite o código de 6 dígitos do aplicativo autenticador.";
const LABEL = "Código do aplicativo autenticador";
const SUBMIT = "Confirmar e gerar";
const SUBMITTING = "Gerando…";
const CANCEL = "Cancelar";
const WRONG_CODE =
  "Código incorreto ou vencido. Aguarde o próximo código do aplicativo e tente de novo.";
const GENERAL_ERROR =
  "Não foi possível gerar novos códigos agora. Tente novamente.";
const RESULT_TITLE = "Seus novos códigos de recuperação";
const RESULT_WARNING =
  "Os códigos anteriores não funcionam mais. Estes aparecem só agora. Guarde-os em um gerenciador de senhas ou em outro lugar seguro.";
const DONE = "Concluir";

function failureMessage(error: Error): string {
  if (error instanceof MfaCodeRejectedError) return WRONG_CODE;
  if (error instanceof LoginBlockedError) return LOGIN_BLOCKED;
  return GENERAL_ERROR;
}

export default function RecoveryCodesSection() {
  const [asking, setAsking] = useState(false);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const regenerate = useRegenerateRecoveryCodes(setCodes);
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<MfaCodeInput, unknown, MfaCodeOutput>({
    resolver: zodResolver(mfaCodeSchema),
    defaultValues: { code: "" },
  });

  function close() {
    setAsking(false);
    setFailure(null);
    reset();
  }

  function onSubmit(values: MfaCodeOutput) {
    setFailure(null);
    regenerate.mutate(values.code, {
      onError: (error) => setFailure(failureMessage(error)),
    });
  }

  if (codes) {
    return (
      <section className="mt-8">
        <RecoveryCodesList
          title={RESULT_TITLE}
          warning={RESULT_WARNING}
          doneLabel={DONE}
          headingLevel="h2"
          codes={codes}
          onDone={() => {
            setCodes(null);
            close();
          }}
        />
      </section>
    );
  }

  return (
    <section className="mt-8">
      <h2 className="text-xl font-bold">{TITLE}</h2>
      <p className="mt-2">{EXPLANATION}</p>
      {asking ? (
        <form
          onSubmit={handleSubmit(onSubmit)}
          noValidate
          className="mt-4 flex flex-col gap-4"
        >
          <p>{INSTRUCTION}</p>
          <div className="flex flex-col gap-2">
            <Label htmlFor="regenerate-code">{LABEL}</Label>
            <Input
              id="regenerate-code"
              inputMode="numeric"
              autoComplete="one-time-code"
              aria-invalid={errors.code ? true : undefined}
              aria-describedby={
                errors.code ? "regenerate-code-error" : undefined
              }
              {...register("code")}
            />
            {errors.code && (
              <p
                id="regenerate-code-error"
                role="alert"
                className="text-sm text-destructive"
              >
                {errors.code.message}
              </p>
            )}
          </div>
          {failure && (
            <p role="alert" className="text-sm text-destructive">
              {failure}
            </p>
          )}
          <div className="flex gap-2">
            <Button type="submit" disabled={regenerate.isPending}>
              {regenerate.isPending ? SUBMITTING : SUBMIT}
            </Button>
            <Button type="button" variant="outline" onClick={close}>
              {CANCEL}
            </Button>
          </div>
        </form>
      ) : (
        <div className="mt-4">
          <Button type="button" onClick={() => setAsking(true)}>
            {START}
          </Button>
        </div>
      )}
    </section>
  );
}

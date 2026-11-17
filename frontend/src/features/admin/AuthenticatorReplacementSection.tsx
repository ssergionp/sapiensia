import { zodResolver } from "@hookform/resolvers/zod";
import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LoginBlockedError } from "@/features/session/api";
import { LOGIN_BLOCKED } from "@/features/session/messages";
import AuthenticatorKey from "./AuthenticatorKey";
import { MfaCodeRejectedError, MfaConflictError, MfaExpiredError } from "./api";
import {
  useConfirmAuthenticatorReplacement,
  useStartAuthenticatorReplacement,
} from "./hooks";
import RecoveryCodesList from "./RecoveryCodesList";
import {
  type MfaCodeInput,
  type MfaCodeOutput,
  type MfaEnrollment,
  mfaCodeSchema,
} from "./schemas";

const TITLE = "Trocar o autenticador";
const EXPLANATION =
  "Use isto se trocou de celular ou quer um aplicativo novo. O autenticador atual continua valendo até você confirmar o novo, e as outras sessões do painel são encerradas.";
const START = "Trocar o autenticador";
const OLD_INSTRUCTION =
  "Para começar, digite o código de 6 dígitos do autenticador atual.";
const RECOVERY_INSTRUCTION =
  "Você entrou com um código de recuperação, então não precisa do código do autenticador atual.";
const OLD_LABEL = "Código do autenticador atual";
const CONTINUE = "Continuar";
const CONTINUING = "Continuando…";
const STEP_2 = "2. Digite o código de 6 dígitos que o aplicativo novo mostra.";
const NEW_LABEL = "Código do aplicativo novo";
const CONFIRM = "Confirmar a troca";
const CONFIRMING = "Confirmando…";
const CANCEL = "Cancelar";
const WRONG_OLD_CODE =
  "Código incorreto ou vencido. Aguarde o próximo código do aplicativo e tente de novo.";
const WRONG_NEW_CODE =
  "Código incorreto ou vencido. Aguarde o próximo código do aplicativo novo e tente de novo.";
const INTERRUPTED = "A troca foi interrompida. Comece de novo.";
const GENERAL_ERROR =
  "Não foi possível trocar o autenticador agora. Tente novamente.";
const RESULT_TITLE = "Autenticador trocado. Seus novos códigos de recuperação";
const RESULT_WARNING =
  "O autenticador anterior e os códigos anteriores não funcionam mais. Estes códigos aparecem só agora. Guarde-os no seu gerenciador de senhas. Quem tiver um código e a sua senha entra no painel.";
const DONE = "Concluir";

type Step = "idle" | "askingOld" | "confirming";

function failureMessage(error: Error, wrongCode: string): string {
  if (error instanceof MfaCodeRejectedError) return wrongCode;
  if (error instanceof LoginBlockedError) return LOGIN_BLOCKED;
  if (error instanceof MfaConflictError) return INTERRUPTED;
  return GENERAL_ERROR;
}

type Props = { oldCodeRequired: boolean };

// The new secret and the recovery codes live only in this component's state: never in the query cache,
// the storages or the logs.
export default function AuthenticatorReplacementSection({
  oldCodeRequired,
}: Props) {
  const [step, setStep] = useState<Step>("idle");
  const [enrollment, setEnrollment] = useState<MfaEnrollment | null>(null);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const start = useStartAuthenticatorReplacement(setEnrollment);
  const confirm = useConfirmAuthenticatorReplacement(setCodes);
  const oldForm = useForm<MfaCodeInput, unknown, MfaCodeOutput>({
    resolver: zodResolver(mfaCodeSchema),
    defaultValues: { code: "" },
  });
  const newForm = useForm<MfaCodeInput, unknown, MfaCodeOutput>({
    resolver: zodResolver(mfaCodeSchema),
    defaultValues: { code: "" },
  });
  const { setFocus: focusOld } = oldForm;
  const { setFocus: focusNew } = newForm;

  useEffect(() => {
    if (step === "askingOld") focusOld("code");
    if (step === "confirming") focusNew("code");
  }, [step, focusOld, focusNew]);

  function reset() {
    setStep("idle");
    setEnrollment(null);
    setFailure(null);
    oldForm.reset();
    newForm.reset();
    // Detach the finished mutations so gcTime: 0 can drop them from the cache.
    start.reset();
    confirm.reset();
  }

  function begin(code: string | undefined) {
    setFailure(null);
    start.mutate(code, {
      onSuccess: () => setStep("confirming"),
      // On 401 the hook already marked the session as ended and the page redirects; no message here.
      onError: (error) => {
        if (!(error instanceof MfaExpiredError)) {
          setFailure(failureMessage(error, WRONG_OLD_CODE));
        }
      },
    });
  }

  function onStartClick() {
    setFailure(null);
    if (oldCodeRequired) setStep("askingOld");
    else begin(undefined);
  }

  function onConfirm(values: MfaCodeOutput) {
    setFailure(null);
    confirm.mutate(values.code, {
      onError: (error) => {
        if (error instanceof MfaExpiredError) return;
        setFailure(failureMessage(error, WRONG_NEW_CODE));
        if (error instanceof MfaConflictError) {
          setStep("idle");
          setEnrollment(null);
          newForm.reset();
        }
      },
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
            reset();
          }}
        />
      </section>
    );
  }

  const failureAlert = failure && (
    <p role="alert" className="text-sm text-destructive">
      {failure}
    </p>
  );

  return (
    <section className="mt-8">
      <h2 className="text-xl font-bold">{TITLE}</h2>
      <p className="mt-2">{EXPLANATION}</p>
      {step === "idle" && (
        <div className="mt-4 flex flex-col gap-4">
          {!oldCodeRequired && <p>{RECOVERY_INSTRUCTION}</p>}
          {failureAlert}
          <div>
            <Button
              type="button"
              disabled={start.isPending}
              onClick={onStartClick}
            >
              {start.isPending ? CONTINUING : START}
            </Button>
          </div>
        </div>
      )}
      {step === "askingOld" && (
        <form
          onSubmit={oldForm.handleSubmit((values) => begin(values.code))}
          noValidate
          className="mt-4 flex flex-col gap-4"
        >
          <p>{OLD_INSTRUCTION}</p>
          <div className="flex flex-col gap-2">
            <Label htmlFor="replace-old-code">{OLD_LABEL}</Label>
            <Input
              id="replace-old-code"
              inputMode="numeric"
              autoComplete="one-time-code"
              aria-invalid={oldForm.formState.errors.code ? true : undefined}
              aria-describedby={
                oldForm.formState.errors.code
                  ? "replace-old-code-error"
                  : undefined
              }
              {...oldForm.register("code")}
            />
            {oldForm.formState.errors.code && (
              <p
                id="replace-old-code-error"
                role="alert"
                className="text-sm text-destructive"
              >
                {oldForm.formState.errors.code.message}
              </p>
            )}
          </div>
          {failureAlert}
          <div className="flex gap-2">
            <Button type="submit" disabled={start.isPending}>
              {start.isPending ? CONTINUING : CONTINUE}
            </Button>
            <Button
              type="button"
              variant="outline"
              disabled={start.isPending}
              onClick={reset}
            >
              {CANCEL}
            </Button>
          </div>
        </form>
      )}
      {step === "confirming" && enrollment && (
        <form
          onSubmit={newForm.handleSubmit(onConfirm)}
          noValidate
          className="mt-4 flex flex-col gap-4"
        >
          <div>
            <AuthenticatorKey
              secret={enrollment.secret}
              otpauthUri={enrollment.otpauthUri}
            />
            <p className="mt-4">{STEP_2}</p>
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="replace-new-code">{NEW_LABEL}</Label>
            <Input
              id="replace-new-code"
              inputMode="numeric"
              autoComplete="one-time-code"
              aria-invalid={newForm.formState.errors.code ? true : undefined}
              aria-describedby={
                newForm.formState.errors.code
                  ? "replace-new-code-error"
                  : undefined
              }
              {...newForm.register("code")}
            />
            {newForm.formState.errors.code && (
              <p
                id="replace-new-code-error"
                role="alert"
                className="text-sm text-destructive"
              >
                {newForm.formState.errors.code.message}
              </p>
            )}
          </div>
          {failureAlert}
          <div className="flex gap-2">
            <Button type="submit" disabled={confirm.isPending}>
              {confirm.isPending ? CONFIRMING : CONFIRM}
            </Button>
            <Button
              type="button"
              variant="outline"
              disabled={confirm.isPending}
              onClick={reset}
            >
              {CANCEL}
            </Button>
          </div>
        </form>
      )}
    </section>
  );
}

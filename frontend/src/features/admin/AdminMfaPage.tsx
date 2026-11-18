import { zodResolver } from "@hookform/resolvers/zod";
import { useCallback, useEffect, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { Link, Navigate, useNavigate } from "react-router";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LoginBlockedError } from "@/features/session/api";
import {
  LOGIN_BLOCKED,
  LOGIN_UNEXPECTED_ERROR,
} from "@/features/session/messages";
import AuthenticatorKey from "./AuthenticatorKey";
import {
  MfaCodeRejectedError,
  MfaConflictError,
  MfaExpiredError,
  startMfaEnrollment,
} from "./api";
import { useMfaStatus, useMfaSubmit } from "./hooks";
import RecoveryCodesList from "./RecoveryCodesList";
import {
  type MfaCodeInput,
  type MfaCodeOutput,
  type MfaEnrollment,
  mfaCodeSchema,
  type RecoveryCodeInput,
  type RecoveryCodeOutput,
  recoveryCodeSchema,
} from "./schemas";

const LOADING = "Carregando…";
const LOAD_ERROR =
  "Não foi possível carregar esta página agora. Tente novamente.";
const EXPIRED =
  "O tempo para digitar o código acabou. Entre de novo com a sua senha.";
const BACK_TO_LOGIN = "Voltar para a entrada";
const WRONG_CODE =
  "Código incorreto ou vencido. Confira o aplicativo e tente de novo.";
const ENROLLMENT_INTERRUPTED =
  "A ativação foi interrompida. Leia o novo QR code e digite o código de novo.";

const ENROLL_TITLE = "Ative a verificação em duas etapas";
const ENROLL_INTRO =
  "Para proteger o painel, a entrada pede a senha e um código do aplicativo autenticador do seu celular.";
const ENROLL_STEP_2 =
  "2. Digite o código de 6 dígitos que o aplicativo mostra.";
const ENROLL_SUBMIT = "Ativar e entrar";
const ENROLL_SUBMITTING = "Ativando…";

const VERIFY_TITLE = "Verificação em duas etapas";
const VERIFY_INSTRUCTION =
  "Digite o código de 6 dígitos do aplicativo autenticador.";
const VERIFY_SUBMIT = "Entrar";
const VERIFY_SUBMITTING = "Entrando…";

const CODE_LABEL = "Código";

const CODES_TITLE = "Guarde seus códigos de recuperação";
const CODES_INTRO =
  "Se você perder o celular, cada código abaixo permite entrar uma única vez no lugar do código do aplicativo.";
const CODES_WARNING =
  "Estes códigos aparecem só agora. Guarde-os no seu gerenciador de senhas. Quem tiver um código e a sua senha entra no painel.";
const CODES_CONTINUE = "Continuar para o painel";

const USE_RECOVERY = "Perdeu o celular? Use um código de recuperação";
const RECOVERY_TITLE = "Entrar com código de recuperação";
const RECOVERY_INSTRUCTION =
  "Digite um dos seus códigos de recuperação. Cada código só funciona uma vez.";
const RECOVERY_LABEL = "Código de recuperação";
const RECOVERY_WRONG =
  "Código de recuperação incorreto ou já usado. Confira e tente de novo.";
const USE_APP_CODE = "Usar o código do aplicativo";

type Mode = "enroll" | "verify";

function submitFailure(error: Error, recovery: boolean): string {
  if (error instanceof MfaCodeRejectedError)
    return recovery ? RECOVERY_WRONG : WRONG_CODE;
  if (error instanceof LoginBlockedError) return LOGIN_BLOCKED;
  return LOGIN_UNEXPECTED_ERROR;
}

export default function AdminMfaPage() {
  const status = useMfaStatus();
  const navigate = useNavigate();
  const [modeOverride, setModeOverride] = useState<Mode | null>(null);
  const [enrollment, setEnrollment] = useState<MfaEnrollment | null>(null);
  const [expired, setExpired] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [recovery, setRecovery] = useState(false);
  // The recovery codes live only here: never in the query cache, the storages or the URL.
  const [codes, setCodes] = useState<string[] | null>(null);
  // Each enrollment call replaces the secret on the server, so the first one is guarded against re-runs.
  const enrollmentStarted = useRef(false);
  const submitting = useRef(false);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<MfaCodeInput, unknown, MfaCodeOutput>({
    resolver: zodResolver(mfaCodeSchema),
    defaultValues: { code: "" },
  });

  const {
    register: registerRecovery,
    handleSubmit: handleSubmitRecovery,
    reset: resetRecovery,
    formState: { errors: recoveryErrors },
  } = useForm<RecoveryCodeInput, unknown, RecoveryCodeOutput>({
    resolver: zodResolver(recoveryCodeSchema),
    defaultValues: { code: "" },
  });

  const statusData =
    status.data !== undefined && typeof status.data === "object"
      ? status.data
      : null;
  const mode: Mode =
    modeOverride ?? (statusData?.enrolled === false ? "enroll" : "verify");
  const submit = useMfaSubmit(recovery ? "recovery" : mode, setCodes);

  const beginEnrollment = useCallback(async () => {
    setEnrollment(null);
    try {
      const result = await startMfaEnrollment();
      if (result === "active") {
        // The MFA got active meanwhile: go to the verification quietly, dropping any interruption notice.
        setFailure(null);
        setModeOverride("verify");
      } else {
        setModeOverride("enroll");
        setEnrollment(result);
      }
    } catch (error) {
      if (error instanceof MfaExpiredError) setExpired(true);
      else setLoadFailed(true);
    }
  }, []);

  const shouldEnroll = statusData?.enrolled === false;
  useEffect(() => {
    if (shouldEnroll && !enrollmentStarted.current) {
      enrollmentStarted.current = true;
      void beginEnrollment();
    }
  }, [shouldEnroll, beginEnrollment]);

  function onSubmit(values: MfaCodeOutput) {
    if (submitting.current) return;
    submitting.current = true;
    setFailure(null);
    submit.mutate(values.code, {
      // After an enrollment the recovery codes screen comes first; its button goes to the panel.
      onSuccess: () => {
        if (mode !== "enroll" || recovery) navigate("/painel");
      },
      onError: (error) => {
        if (error instanceof MfaExpiredError) {
          setExpired(true);
        } else if (error instanceof MfaConflictError) {
          reset();
          resetRecovery();
          setRecovery(false);
          enrollmentStarted.current = true;
          if (mode === "enroll") setFailure(ENROLLMENT_INTERRUPTED);
          setModeOverride("enroll");
          void beginEnrollment();
        } else {
          setFailure(submitFailure(error, recovery));
        }
      },
      onSettled: () => {
        submitting.current = false;
      },
    });
  }

  function switchView(toRecovery: boolean) {
    setFailure(null);
    reset();
    resetRecovery();
    setRecovery(toRecovery);
  }

  if (codes) {
    return (
      <main className="auth-panel">
        <RecoveryCodesList
          title={CODES_TITLE}
          intro={CODES_INTRO}
          warning={CODES_WARNING}
          doneLabel={CODES_CONTINUE}
          headingLevel="h1"
          codes={codes}
          onDone={() => {
            setCodes(null);
            navigate("/painel");
          }}
        />
      </main>
    );
  }

  if (status.data === "forbidden") return <Navigate to="/painel" replace />;

  if (expired || status.data === "expired") {
    return (
      <main className="auth-panel">
        <p role="alert">{EXPIRED}</p>
        <p className="mt-4">
          <Link to="/painel/entrar" className="text-link">
            {BACK_TO_LOGIN}
          </Link>
        </p>
      </main>
    );
  }

  if (status.isError || loadFailed) {
    return (
      <main className="auth-panel">
        <p role="alert" className="text-destructive">
          {LOAD_ERROR}
        </p>
      </main>
    );
  }

  if (status.isPending || (mode === "enroll" && enrollment === null)) {
    return (
      <main className="auth-panel">
        <p>{LOADING}</p>
      </main>
    );
  }

  const enrolling = mode === "enroll";

  if (recovery && !enrolling) {
    return (
      <main className="auth-panel">
        <h1 className="text-3xl">{RECOVERY_TITLE}</h1>
        <p className="mt-4">{RECOVERY_INSTRUCTION}</p>
        <form
          onSubmit={handleSubmitRecovery(onSubmit)}
          noValidate
          className="mt-4 flex flex-col gap-4"
        >
          <div className="flex flex-col gap-2">
            <Label htmlFor="recovery-code">{RECOVERY_LABEL}</Label>
            <Input
              id="recovery-code"
              autoComplete="off"
              autoCapitalize="characters"
              spellCheck={false}
              aria-invalid={recoveryErrors.code ? true : undefined}
              aria-describedby={
                recoveryErrors.code ? "recovery-code-error" : undefined
              }
              {...registerRecovery("code")}
            />
            {recoveryErrors.code && (
              <p
                id="recovery-code-error"
                role="alert"
                className="text-sm text-destructive"
              >
                {recoveryErrors.code.message}
              </p>
            )}
          </div>
          {failure && (
            <p role="alert" className="text-sm text-destructive">
              {failure}
            </p>
          )}
          <Button
            type="submit"
            disabled={submit.isPending}
            className="mt-2 h-11 w-full text-base"
          >
            {submit.isPending ? VERIFY_SUBMITTING : VERIFY_SUBMIT}
          </Button>
          <Button
            type="button"
            variant="link"
            onClick={() => switchView(false)}
          >
            {USE_APP_CODE}
          </Button>
        </form>
      </main>
    );
  }

  return (
    <main className="auth-panel">
      <h1 className="text-3xl">{enrolling ? ENROLL_TITLE : VERIFY_TITLE}</h1>
      {enrolling && enrollment ? (
        <>
          <p className="mt-4">{ENROLL_INTRO}</p>
          <AuthenticatorKey
            secret={enrollment.secret}
            otpauthUri={enrollment.otpauthUri}
          />
          <p className="mt-4">{ENROLL_STEP_2}</p>
        </>
      ) : (
        <p className="mt-4">{VERIFY_INSTRUCTION}</p>
      )}
      <form
        onSubmit={handleSubmit(onSubmit)}
        noValidate
        className="mt-4 flex flex-col gap-4"
      >
        <div className="flex flex-col gap-2">
          <Label htmlFor="code">{CODE_LABEL}</Label>
          <Input
            id="code"
            inputMode="numeric"
            autoComplete="one-time-code"
            aria-invalid={errors.code ? true : undefined}
            aria-describedby={errors.code ? "code-error" : undefined}
            {...register("code")}
          />
          {errors.code && (
            <p
              id="code-error"
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
        <Button
          type="submit"
          disabled={submit.isPending}
          className="mt-2 h-11 w-full text-base"
        >
          {submit.isPending
            ? enrolling
              ? ENROLL_SUBMITTING
              : VERIFY_SUBMITTING
            : enrolling
              ? ENROLL_SUBMIT
              : VERIFY_SUBMIT}
        </Button>
        {!enrolling && (
          <Button type="button" variant="link" onClick={() => switchView(true)}>
            {USE_RECOVERY}
          </Button>
        )}
      </form>
    </main>
  );
}

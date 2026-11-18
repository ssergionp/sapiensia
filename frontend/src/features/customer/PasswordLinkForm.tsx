import { zodResolver } from "@hookform/resolvers/zod";
import type { UseMutationResult } from "@tanstack/react-query";
import { type ReactNode, useState } from "react";
import { useForm } from "react-hook-form";
import { useLocation } from "react-router";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { TOO_MANY_REQUESTS, TooManyRequestsError } from "@/lib/tooManyRequests";
import { ResetPasswordError } from "./api";
import {
  errorMessages,
  type ResetPasswordInput,
  resetPasswordSchema,
  SAVE_UNEXPECTED_ERROR,
} from "./schemas";

type PasswordLinkMutation = UseMutationResult<
  unknown,
  Error,
  { token: string; newPassword: string }
>;

export type PasswordLinkFormProps = {
  /** Mutation hook that submits the token and the new password. */
  useSubmit: () => PasswordLinkMutation;
  title: string;
  intro: string;
  fieldLabel: string;
  submitLabel: string;
  pendingLabel: string;
  successContent: ReactNode;
  invalidLinkContent: ReactNode;
};

export default function PasswordLinkForm(props: PasswordLinkFormProps) {
  // The token travels in the URL fragment so it is never sent to servers or logs.
  // Read from the router so a newer link opened in the same tab replaces it; the key
  // resets the form and its flags for the new token.
  const token = useLocation().hash.replace(/^#/, "");
  return <PasswordLinkContent key={token} token={token} {...props} />;
}

function PasswordLinkContent({
  token,
  useSubmit,
  title,
  intro,
  fieldLabel,
  submitLabel,
  pendingLabel,
  successContent,
  invalidLinkContent,
}: PasswordLinkFormProps & { token: string }) {
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
  const mutation = useSubmit();

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

  let content: ReactNode;
  if (mutation.isSuccess) {
    content = (
      <p role="status" className="mt-4 rounded-lg bg-muted p-4">
        {successContent}
      </p>
    );
  } else if (!token || tokenRejected) {
    content = (
      <p role="alert" className="mt-4 rounded-lg bg-destructive/10 p-4">
        {invalidLinkContent}
      </p>
    );
  } else {
    content = (
      <>
        <p className="mt-2 text-muted-foreground">{intro}</p>
        <form
          onSubmit={handleSubmit(onSubmit)}
          noValidate
          className="mt-6 flex flex-col gap-4"
        >
          <div className="flex flex-col gap-2">
            <Label htmlFor="newPassword">{fieldLabel}</Label>
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
              {mutation.error instanceof TooManyRequestsError
                ? TOO_MANY_REQUESTS
                : SAVE_UNEXPECTED_ERROR}
            </p>
          )}
          <Button
            type="submit"
            className="mt-2 h-11 w-full text-base"
            disabled={mutation.isPending}
          >
            {mutation.isPending ? pendingLabel : submitLabel}
          </Button>
        </form>
      </>
    );
  }

  return (
    <main className="auth-panel">
      <h1 className="text-3xl">{title}</h1>
      {content}
    </main>
  );
}

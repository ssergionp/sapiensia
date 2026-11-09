import { useId, useState } from "react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";

const LIST_NAME = "Códigos de recuperação";
const COPY = "Copiar códigos";
const COPIED = "Códigos copiados. Cole-os no seu gerenciador de senhas agora.";
const COPY_FAILED =
  "Não foi possível copiar. Selecione os códigos e copie manualmente.";
const SAVED = "Guardei meus códigos em um lugar seguro";

type Props = {
  title: string;
  intro?: string;
  warning: string;
  doneLabel: string;
  headingLevel: "h1" | "h2";
  codes: string[];
  onDone: () => void;
};

// The codes are shown only here and only once; the parent owns them in plain state.
export default function RecoveryCodesList({
  title,
  intro,
  warning,
  doneLabel,
  headingLevel: Heading,
  codes,
  onDone,
}: Props) {
  const [saved, setSaved] = useState(false);
  const [copyResult, setCopyResult] = useState<"ok" | "failed" | null>(null);
  const checkboxId = useId();

  async function copy() {
    try {
      await navigator.clipboard.writeText(codes.join("\n"));
      setCopyResult("ok");
    } catch {
      setCopyResult("failed");
    }
  }

  return (
    <div>
      <Heading className="text-2xl font-bold">{title}</Heading>
      {intro && <p className="mt-4">{intro}</p>}
      <p className="mt-4 font-semibold">{warning}</p>
      <ol
        aria-label={LIST_NAME}
        className="mt-4 grid gap-1 font-mono text-lg [overflow-wrap:anywhere]"
      >
        {codes.map((code) => (
          <li key={code}>{code}</li>
        ))}
      </ol>
      <div className="mt-4">
        <Button type="button" variant="outline" onClick={() => void copy()}>
          {COPY}
        </Button>
      </div>
      {copyResult === "ok" && (
        <p role="status" className="mt-2 text-sm">
          {COPIED}
        </p>
      )}
      {copyResult === "failed" && (
        <p role="alert" className="mt-2 text-sm text-destructive">
          {COPY_FAILED}
        </p>
      )}
      <div className="mt-4 flex items-center gap-2">
        <input
          id={checkboxId}
          type="checkbox"
          checked={saved}
          onChange={(event) => setSaved(event.target.checked)}
          className="size-4"
        />
        <Label htmlFor={checkboxId}>{SAVED}</Label>
      </div>
      <div className="mt-4">
        <Button type="button" disabled={!saved} onClick={onDone}>
          {doneLabel}
        </Button>
      </div>
    </div>
  );
}

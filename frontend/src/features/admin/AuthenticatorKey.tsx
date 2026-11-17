import { QRCodeSVG } from "qrcode.react";

const STEP_1 = "1. No aplicativo autenticador, leia o QR code abaixo.";
const QR_NAME = "QR code para o aplicativo autenticador";
const ALTERNATIVE =
  "Não consegue ler o QR code? Digite esta chave no aplicativo:";

function groupKey(secret: string): string {
  return secret.replace(/(.{4})(?=.)/g, "$1 ");
}

type Props = { secret: string; otpauthUri: string };

// The QR code and the manual key of a new authenticator secret; the parent owns the secret in plain state.
export default function AuthenticatorKey({ secret, otpauthUri }: Props) {
  return (
    <>
      <p className="mt-4">{STEP_1}</p>
      <div className="mt-2 inline-block rounded-md bg-white p-3">
        <QRCodeSVG
          value={otpauthUri}
          size={192}
          role="img"
          aria-label={QR_NAME}
        />
      </div>
      <p className="mt-4">{ALTERNATIVE}</p>
      <p className="mt-1 font-mono text-lg font-semibold [overflow-wrap:anywhere]">
        {groupKey(secret)}
      </p>
    </>
  );
}

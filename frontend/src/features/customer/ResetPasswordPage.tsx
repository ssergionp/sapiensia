import { Link } from "react-router";
import { useResetPassword } from "./hooks";
import PasswordLinkForm from "./PasswordLinkForm";

export default function ResetPasswordPage() {
  return (
    <PasswordLinkForm
      useSubmit={useResetPassword}
      title="Nova senha"
      intro="Escolha a nova senha da sua conta."
      fieldLabel="Nova senha"
      submitLabel="Salvar nova senha"
      pendingLabel="Salvando…"
      successContent={
        <>
          Senha alterada. Por segurança, você saiu de todos os aparelhos. Entre
          com a nova senha.{" "}
          <Link to="/entrar" className="underline">
            Entrar
          </Link>
        </>
      }
      invalidLinkContent={
        <>
          Este link venceu ou já foi usado. Peça um novo link em "
          <Link to="/esqueci-senha" className="underline">
            Esqueci minha senha
          </Link>
          ".
        </>
      }
    />
  );
}

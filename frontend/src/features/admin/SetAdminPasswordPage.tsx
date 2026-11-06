import { Link } from "react-router";
import PasswordLinkForm from "@/features/customer/PasswordLinkForm";
import { useSetAdminPassword } from "./hooks";

export default function SetAdminPasswordPage() {
  return (
    <PasswordLinkForm
      useSubmit={useSetAdminPassword}
      title="Definir senha do painel"
      intro="Escolha a senha da sua conta de Administrador."
      fieldLabel="Nova senha"
      submitLabel="Salvar senha"
      pendingLabel="Salvando…"
      successContent={
        <>
          Senha definida. Entre no painel com a nova senha.{" "}
          <Link to="/painel/entrar" className="underline">
            Entrar no painel
          </Link>
        </>
      }
      invalidLinkContent="Este link venceu ou já foi usado. Se você ainda não definiu a senha, um novo link chega por e-mail quando o sistema for reiniciado."
    />
  );
}

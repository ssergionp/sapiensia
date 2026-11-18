import type { ReactNode } from "react";
import { Link, Navigate } from "react-router";
import AdminNav from "./AdminNav";
import { useAdminSession } from "./hooks";
import type { AdminSession } from "./schemas";

const LOAD_ERROR = "Não foi possível carregar o painel agora. Tente novamente.";

type Props = {
  children: (session: AdminSession) => ReactNode;
  // When set, an Administrator without this capability sees `missingMessage` instead of the children.
  capability?: string;
  missingMessage?: string;
};

// Loading, load error, redirect to the login and "only for the Administrator" for every panel page.
export default function AdminGuard({
  children,
  capability,
  missingMessage,
}: Props) {
  const session = useAdminSession();

  if (session.isPending) {
    return (
      <main className="page-container">
        <p>Carregando…</p>
      </main>
    );
  }

  // A failed background refetch keeps the previous data; it must not replace a screen that is already showing.
  if (session.isError && session.data === undefined) {
    return (
      <main className="page-container">
        <p role="alert" className="text-destructive">
          {LOAD_ERROR}
        </p>
      </main>
    );
  }

  if (session.data === null) {
    return <Navigate to="/painel/entrar" replace />;
  }

  if (session.data === "forbidden") {
    return (
      <main className="page-container">
        <p role="alert">Esta área é só para o Administrador.</p>
        <p className="mt-4">
          <Link to="/" className="text-link">
            Voltar ao início
          </Link>
        </p>
      </main>
    );
  }

  // The panel bar uses the session loaded above: no extra request, and never on a page without a full session.
  return (
    <>
      <AdminNav session={session.data} />
      {capability && !session.data.capabilities.includes(capability) ? (
        <main className="page-container">
          <p role="alert">{missingMessage}</p>
        </main>
      ) : (
        children(session.data)
      )}
    </>
  );
}

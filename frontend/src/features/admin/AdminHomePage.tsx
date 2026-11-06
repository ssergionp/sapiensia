import { useQueryClient } from "@tanstack/react-query";
import { Link, Navigate, useNavigate } from "react-router";
import { Button } from "@/components/ui/button";
import { useLogout } from "@/features/session/hooks";
import { LOGOUT_ERROR } from "@/features/session/messages";
import { adminSessionQueryKey, useAdminSession } from "./hooks";

const LOAD_ERROR = "Não foi possível carregar o painel agora. Tente novamente.";

export default function AdminHomePage() {
  const session = useAdminSession();
  const logout = useLogout();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  function signOut() {
    logout.mutate(undefined, {
      onSuccess: () => {
        navigate("/painel/entrar", { replace: true });
        // Mark the cached administrator session as signed out; unlike removing the query,
        // this cannot trigger a refetch while the observer is still mounted.
        queryClient.setQueryData(adminSessionQueryKey, null);
      },
    });
  }

  if (session.isPending) {
    return (
      <main className="p-8">
        <p>Carregando…</p>
      </main>
    );
  }

  if (session.isError) {
    return (
      <main className="p-8">
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
      <main className="p-8">
        <p role="alert">Esta área é só para o Administrador.</p>
        <p className="mt-4">
          <Link to="/" className="underline">
            Voltar ao início
          </Link>
        </p>
      </main>
    );
  }

  return (
    <main className="p-8">
      <h1 className="text-2xl font-bold">Painel do Administrador</h1>
      <p className="mt-4">
        Você entrou como{" "}
        <span className="[overflow-wrap:anywhere]">{session.data.email}</span>.
      </p>
      {logout.isError && (
        <p role="alert" className="mt-2 text-sm text-destructive">
          {LOGOUT_ERROR}
        </p>
      )}
      <div className="mt-4">
        <Button disabled={logout.isPending} onClick={signOut}>
          {logout.isPending ? "Saindo…" : "Sair"}
        </Button>
      </div>
    </main>
  );
}

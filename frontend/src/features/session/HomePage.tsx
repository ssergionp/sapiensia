import { Link } from "react-router";
import { Button } from "@/components/ui/button";
import { useLogout, useLogoutEverywhere, useSession } from "./hooks";

const LOGOUT_ERROR = "Não foi possível sair agora. Tente novamente.";
const LOGOUT_ALL_ERROR =
  "Não foi possível sair de todos os aparelhos agora. Tente novamente.";
const LOGOUT_ALL_SUCCESS = "Você saiu de todos os aparelhos.";

export default function HomePage() {
  const session = useSession();
  const logout = useLogout();
  const logoutEverywhere = useLogoutEverywhere();

  if (session.isPending) {
    return (
      <main className="p-8">
        <p>Carregando…</p>
      </main>
    );
  }

  if (!session.data) {
    return (
      <main className="p-8">
        {logoutEverywhere.isSuccess && (
          <p role="status" className="mb-4">
            {LOGOUT_ALL_SUCCESS}
          </p>
        )}
        <nav className="flex gap-4">
          <Link to="/entrar" className="underline">
            Entrar
          </Link>
          <Link to="/cadastro" className="underline">
            Criar conta
          </Link>
        </nav>
      </main>
    );
  }

  return (
    <main className="p-8">
      <p>Você entrou como {session.data.email}.</p>
      {logout.isError && (
        <p role="alert" className="mt-2 text-sm text-destructive">
          {LOGOUT_ERROR}
        </p>
      )}
      {logoutEverywhere.isError && (
        <p role="alert" className="mt-2 text-sm text-destructive">
          {LOGOUT_ALL_ERROR}
        </p>
      )}
      <div className="mt-4 flex gap-4">
        <Button disabled={logout.isPending} onClick={() => logout.mutate()}>
          {logout.isPending ? "Saindo…" : "Sair"}
        </Button>
        <Button
          variant="outline"
          disabled={logoutEverywhere.isPending}
          onClick={() => logoutEverywhere.mutate()}
        >
          {logoutEverywhere.isPending
            ? "Saindo de todos os aparelhos…"
            : "Sair de todos os aparelhos"}
        </Button>
      </div>
    </main>
  );
}

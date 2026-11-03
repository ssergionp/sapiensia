import { Link } from "react-router";
import { Button } from "@/components/ui/button";
import { useLogout, useSession } from "./hooks";

const LOGOUT_ERROR = "Não foi possível sair agora. Tente novamente.";

export default function HomePage() {
  const session = useSession();
  const logout = useLogout();

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
      <Button
        className="mt-4"
        disabled={logout.isPending}
        onClick={() => logout.mutate()}
      >
        {logout.isPending ? "Saindo…" : "Sair"}
      </Button>
    </main>
  );
}

import { useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router";
import { Button } from "@/components/ui/button";
import { useLogout } from "@/features/session/hooks";
import { LOGOUT_ERROR } from "@/features/session/messages";
import AdminGuard from "./AdminGuard";
import AuthenticatorReplacementSection from "./AuthenticatorReplacementSection";
import { adminSessionQueryKey } from "./hooks";
import RecoveryCodesSection from "./RecoveryCodesSection";

export default function AdminHomePage() {
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

  return (
    <AdminGuard>
      {(admin) => (
        <main className="p-8">
          <h1 className="text-2xl font-bold">Painel do Administrador</h1>
          <p className="mt-4">
            Você entrou como{" "}
            <span className="[overflow-wrap:anywhere]">{admin.email}</span>.
          </p>
          {admin.capabilities.includes("PRODUCTS_EDIT") && (
            <p className="mt-4">
              <Link to="/painel/produtos" className="underline">
                Produtos
              </Link>
            </p>
          )}
          {admin.capabilities.includes("ORDERS_VIEW") && (
            <p className="mt-4">
              <Link to="/painel/pedidos" className="underline">
                Pedidos
              </Link>
            </p>
          )}
          <RecoveryCodesSection />
          <AuthenticatorReplacementSection
            oldCodeRequired={admin.oldCodeRequired !== false}
          />
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
      )}
    </AdminGuard>
  );
}

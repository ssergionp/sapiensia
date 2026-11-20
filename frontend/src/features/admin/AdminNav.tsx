import { useQueryClient } from "@tanstack/react-query";
import { cn } from "cn";
import { NavLink, useNavigate } from "react-router";
import { CHROME_FOCUS_RING, NAV_LINK } from "@/components/brand/brandTexts";
import { Button } from "@/components/ui/button";
import { useLogout } from "@/features/session/hooks";
import { LOGOUT_ERROR } from "@/features/session/messages";
import { adminSessionQueryKey } from "./hooks";
import type { AdminSession } from "./schemas";

const NAV_TEXTS = {
  label: "Navegação do painel",
  home: "Painel",
  products: "Produtos",
  orders: "Pedidos",
  refunds: "Reembolsos",
  signOut: "Sair",
  signingOut: "Saindo…",
} as const;

// Panel navigation under the site header. It receives the session the AdminGuard already loaded, so it
// never asks the API again, and it only exists on pages with a complete Administrator session.
export default function AdminNav({ session }: { session: AdminSession }) {
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
    <div className="border-b bg-card">
      <nav
        aria-label={NAV_TEXTS.label}
        className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-1 px-1 sm:px-3"
      >
        <NavLink to="/painel" end className={NAV_LINK}>
          {NAV_TEXTS.home}
        </NavLink>
        {session.capabilities.includes("PRODUCTS_EDIT") && (
          <NavLink to="/painel/produtos" className={NAV_LINK}>
            {NAV_TEXTS.products}
          </NavLink>
        )}
        {session.capabilities.includes("ORDERS_VIEW") && (
          <NavLink to="/painel/pedidos" className={NAV_LINK}>
            {NAV_TEXTS.orders}
          </NavLink>
        )}
        {session.capabilities.includes("REFUNDS_VIEW") && (
          <NavLink to="/painel/reembolsos" className={NAV_LINK}>
            {NAV_TEXTS.refunds}
          </NavLink>
        )}
        <Button
          type="button"
          variant="ghost"
          className={cn("ml-auto h-11 px-3 font-semibold", CHROME_FOCUS_RING)}
          disabled={logout.isPending}
          onClick={signOut}
        >
          {logout.isPending ? NAV_TEXTS.signingOut : NAV_TEXTS.signOut}
        </Button>
        {logout.isError && (
          <p role="alert" className="w-full px-3 pb-2 text-sm text-destructive">
            {LOGOUT_ERROR}
          </p>
        )}
      </nav>
    </div>
  );
}

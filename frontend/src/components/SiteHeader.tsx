import { cn } from "cn";
import { useEffect, useRef } from "react";
import { Link, NavLink, useLocation, useNavigate } from "react-router";
import {
  BRAND_TEXTS,
  CHROME_FOCUS_RING,
  NAV_LINK,
} from "@/components/brand/brandTexts";
import Logo from "@/components/brand/Logo";
import ThemeToggle from "@/components/ThemeToggle";
import { Button, buttonVariants } from "@/components/ui/button";
import { useLogout, useSession } from "@/features/session/hooks";
import { LOGOUT_ERROR } from "@/features/session/messages";

const NAV_TEXTS = {
  label: "Navegação principal",
  catalog: "Catálogo",
  library: "Biblioteca",
  orders: "Meus Pedidos",
  signIn: "Entrar",
  signOut: "Sair",
  signingOut: "Saindo…",
} as const;

function isPanel(pathname: string) {
  return pathname === "/painel" || pathname.startsWith("/painel/");
}

// Brand, navigation and the account action. The admin panel keeps brand + theme only and never asks for
// the customer session (an administrator would get 403 from /api/sessions/current).
export default function SiteHeader() {
  const { pathname } = useLocation();
  return (
    <header className="border-b bg-card">
      <div className="mx-auto flex min-h-16 max-w-6xl flex-wrap items-center gap-x-2 px-4 sm:px-6">
        <Link
          to="/"
          aria-label={BRAND_TEXTS.homeLink}
          className={`mr-auto -ml-1 rounded-md px-1 py-1 sm:mr-4 ${CHROME_FOCUS_RING}`}
        >
          <Logo />
        </Link>
        {isPanel(pathname) ? (
          <span className="sm:ml-auto" />
        ) : (
          <CustomerNavigation pathname={pathname} />
        )}
        <ThemeToggle />
      </div>
    </header>
  );
}

function CustomerNavigation({ pathname }: { pathname: string }) {
  const session = useSession();
  const navigate = useNavigate();
  // The session refresh waits until the home page is on screen (see useLogout).
  const arrivedHome = useRef<(() => void) | null>(null);
  useEffect(() => {
    if (pathname === "/") {
      arrivedHome.current?.();
      arrivedHome.current = null;
    }
  }, [pathname]);
  const logout = useLogout(() => {
    if (pathname === "/") return;
    navigate("/");
    return new Promise<void>((resolve) => {
      arrivedHome.current = resolve;
    });
  });
  const signedIn = Boolean(session.data);
  // While the session loads nothing account-related shows, so the header never flashes the signed-out
  // state; if it fails to load, "Entrar" is the way out. On /entrar itself the link would only repeat the page.
  const showSignIn =
    (session.data === null || session.isError) && pathname !== "/entrar";

  return (
    <>
      <nav
        aria-label={NAV_TEXTS.label}
        className="order-last -mx-3 flex w-full gap-1 pb-1 sm:order-none sm:mx-0 sm:mr-auto sm:w-auto sm:pb-0"
      >
        <NavLink to="/catalogo" className={NAV_LINK}>
          {NAV_TEXTS.catalog}
        </NavLink>
        {signedIn && (
          <>
            <NavLink to="/biblioteca" className={NAV_LINK}>
              {NAV_TEXTS.library}
            </NavLink>
            <NavLink to="/pedidos" className={NAV_LINK}>
              {NAV_TEXTS.orders}
            </NavLink>
          </>
        )}
      </nav>
      {showSignIn && (
        <Link
          to="/entrar"
          className={cn(buttonVariants(), "h-11 px-4", CHROME_FOCUS_RING)}
        >
          {NAV_TEXTS.signIn}
        </Link>
      )}
      {signedIn && (
        <Button
          type="button"
          variant="ghost"
          className={cn("h-11 px-3 font-semibold", CHROME_FOCUS_RING)}
          disabled={logout.isPending}
          onClick={() => logout.mutate()}
        >
          {logout.isPending ? NAV_TEXTS.signingOut : NAV_TEXTS.signOut}
        </Button>
      )}
      {logout.isError && (
        <p
          role="alert"
          className="order-last w-full pb-2 text-sm text-destructive"
        >
          {LOGOUT_ERROR}
        </p>
      )}
    </>
  );
}

import { cn } from "cn";
import type { ReactNode } from "react";
import { Link } from "react-router";
import { BRAND_TEXTS } from "@/components/brand/brandTexts";
import { Button, buttonVariants } from "@/components/ui/button";
import { CATALOG_TEXTS } from "@/features/catalog/messages";
import { DEFAULT_TITLE, useDocumentTitle } from "@/lib/useDocumentTitle";
import { useLogoutEverywhere, useSession } from "./hooks";

const HERO_SUPPORT =
  "E-books, cartilhas, tutoriais e checklists para estudar e aplicar no dia a dia.";
const LOGOUT_ALL_ERROR =
  "Não foi possível sair de todos os aparelhos agora. Tente novamente.";
const LOGOUT_ALL_SUCCESS = "Você saiu de todos os aparelhos.";

// The tagline is the hero title; "para usar." is set apart in italic and the action color (DESIGN.md).
const [TAGLINE_START, TAGLINE_END] = splitTagline(BRAND_TEXTS.tagline);

function splitTagline(tagline: string): [string, string] {
  const end = "para usar.";
  return tagline.endsWith(end)
    ? [tagline.slice(0, -end.length), end]
    : [tagline, ""];
}

const ACTION = "h-11 px-5 text-base";

// Navigation (Entrar, Biblioteca, Meus Pedidos, Sair) lives in the site header since #186 stage 2.
export default function HomePage() {
  const session = useSession();
  const logoutEverywhere = useLogoutEverywhere();
  useDocumentTitle(DEFAULT_TITLE);

  let content: ReactNode;
  if (session.isPending) {
    content = <p role="status">Carregando…</p>;
  } else if (!session.data) {
    content = (
      <>
        {logoutEverywhere.isSuccess && (
          <p role="status" className="mb-6">
            {LOGOUT_ALL_SUCCESS}
          </p>
        )}
        <div className="flex flex-wrap gap-3">
          <Link to="/catalogo" className={cn(buttonVariants(), ACTION)}>
            {CATALOG_TEXTS.homeLink}
          </Link>
          <Link
            to="/cadastro"
            className={cn(buttonVariants({ variant: "outline" }), ACTION)}
          >
            Criar conta
          </Link>
        </div>
      </>
    );
  } else {
    content = (
      <>
        <p className="mb-6 text-muted-foreground">
          Você entrou como{" "}
          <span className="font-semibold text-foreground [overflow-wrap:anywhere]">
            {session.data.email}
          </span>
          .
        </p>
        {logoutEverywhere.isError && (
          <p role="alert" className="mb-4 text-sm text-destructive">
            {LOGOUT_ALL_ERROR}
          </p>
        )}
        <div className="flex flex-wrap gap-3">
          <Link to="/catalogo" className={cn(buttonVariants(), ACTION)}>
            {CATALOG_TEXTS.homeLink}
          </Link>
          <Button
            variant="outline"
            className={ACTION}
            disabled={logoutEverywhere.isPending}
            onClick={() => logoutEverywhere.mutate()}
          >
            {logoutEverywhere.isPending
              ? "Saindo de todos os aparelhos…"
              : "Sair de todos os aparelhos"}
          </Button>
        </div>
      </>
    );
  }

  return (
    <main className="mx-auto w-full max-w-6xl px-4 py-14 sm:px-6 sm:py-24">
      <h1 className="max-w-3xl text-5xl leading-[1.05] text-balance sm:text-7xl">
        {TAGLINE_START}
        <em className="whitespace-nowrap text-primary">{TAGLINE_END}</em>
      </h1>
      <p className="mt-6 max-w-xl text-lg text-muted-foreground">
        {HERO_SUPPORT}
      </p>
      <div className="mt-10">{content}</div>
    </main>
  );
}

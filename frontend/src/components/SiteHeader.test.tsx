import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mockSystemTheme, resetTheme } from "@/test/systemTheme";
import SiteHeader from "./SiteHeader";

const SESSION_URL = "/api/sessions/current";

function renderHeader(path = "/") {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <SiteHeader />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function session(status: 200 | 401) {
  return vi.spyOn(globalThis, "fetch").mockResolvedValue(
    status === 200
      ? new Response(JSON.stringify({ email: "ana@example.com" }), {
          status,
          headers: { "Content-Type": "application/json" },
        })
      : new Response(null, { status }),
  );
}

const header = () => within(screen.getByRole("banner"));

describe("SiteHeader", () => {
  beforeEach(() => {
    mockSystemTheme(false);
  });
  afterEach(resetTheme);

  it("mostra a marca com link para a página inicial", () => {
    session(401);
    renderHeader();
    expect(
      screen.getByRole("link", { name: "SapiensIA — página inicial" }),
    ).toHaveAttribute("href", "/");
  });

  it("tem o botão de trocar o tema", () => {
    session(401);
    renderHeader();
    expect(
      screen.getByRole("button", { name: "Usar tema escuro" }),
    ).toBeInTheDocument();
  });

  it("sem sessão, mostra Catálogo e Entrar", async () => {
    session(401);
    renderHeader();

    expect(
      await header().findByRole("link", { name: "Entrar" }),
    ).toHaveAttribute("href", "/entrar");
    const nav = within(
      screen.getByRole("navigation", { name: "Navegação principal" }),
    );
    expect(nav.getByRole("link", { name: "Catálogo" })).toHaveAttribute(
      "href",
      "/catalogo",
    );
    expect(nav.queryByRole("link", { name: "Biblioteca" })).toBeNull();
    expect(header().queryByRole("button", { name: "Sair" })).toBeNull();
  });

  it("com sessão, mostra Catálogo, Biblioteca, Meus Pedidos e Sair", async () => {
    session(200);
    renderHeader();

    expect(
      await header().findByRole("button", { name: "Sair" }),
    ).toBeInTheDocument();
    const nav = within(
      screen.getByRole("navigation", { name: "Navegação principal" }),
    );
    expect(nav.getByRole("link", { name: "Catálogo" })).toHaveAttribute(
      "href",
      "/catalogo",
    );
    expect(nav.getByRole("link", { name: "Biblioteca" })).toHaveAttribute(
      "href",
      "/biblioteca",
    );
    expect(nav.getByRole("link", { name: "Meus Pedidos" })).toHaveAttribute(
      "href",
      "/pedidos",
    );
    expect(header().queryByRole("link", { name: "Entrar" })).toBeNull();
  });

  it("no painel, não consulta a sessão do Cliente nem mostra a navegação", () => {
    const fetchSpy = session(200);
    renderHeader("/painel/pedidos");

    expect(fetchSpy.mock.calls.map(([url]) => String(url))).not.toContain(
      SESSION_URL,
    );
    expect(screen.queryByRole("navigation")).toBeNull();
    expect(
      screen.getByRole("button", { name: "Usar tema escuro" }),
    ).toBeInTheDocument();
  });

  it("em /entrar, não repete o link Entrar", async () => {
    const fetchSpy = session(401);
    renderHeader("/entrar");

    await vi.waitFor(() =>
      expect(fetchSpy.mock.calls.map(([url]) => String(url))).toContain(
        SESSION_URL,
      ),
    );
    expect(
      await header().findByRole("link", { name: "Catálogo" }),
    ).toBeInTheDocument();
    expect(header().queryByRole("link", { name: "Entrar" })).toBeNull();
  });

  it("marca a página atual", async () => {
    session(200);
    renderHeader("/pedidos/42");

    expect(
      await header().findByRole("link", { name: "Meus Pedidos" }),
    ).toHaveAttribute("aria-current", "page");
    expect(
      header().getByRole("link", { name: "Biblioteca" }),
    ).not.toHaveAttribute("aria-current");
  });

  it("enquanto a sessão carrega, mostra só o Catálogo e nenhum Carregando…", () => {
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => {}));
    renderHeader();

    expect(
      header().getByRole("link", { name: "Catálogo" }),
    ).toBeInTheDocument();
    expect(header().queryByRole("link", { name: "Entrar" })).toBeNull();
    expect(header().queryByText("Carregando…")).toBeNull();
  });
});

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useEffect } from "react";
import {
  MemoryRouter,
  Route,
  Routes,
  useLocation,
  useNavigate,
} from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useSession } from "@/features/session/hooks";
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

// Stands in for /pedidos, /biblioteca and /pedidos/:id: with no session they send the visitor to /entrar.
function GuardedPage() {
  const session = useSession();
  const navigate = useNavigate();
  useEffect(() => {
    if (session.data === null) navigate("/entrar");
  }, [session.data, navigate]);
  return <p>Página protegida</p>;
}

function Where({ visited }: { visited: string[] }) {
  const { pathname } = useLocation();
  visited.push(pathname);
  return <p data-testid="where">{pathname}</p>;
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

  it("depois de Sair, vai para a página inicial sem passar por /entrar", async () => {
    let signedOut = false;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const url = String(input);
      if (init?.method === "DELETE") {
        signedOut = true;
        return new Response(null, { status: 204 });
      }
      if (url === SESSION_URL)
        return signedOut
          ? new Response(null, { status: 401 })
          : new Response(JSON.stringify({ email: "ana@example.com" }), {
              status: 200,
              headers: { "Content-Type": "application/json" },
            });
      return new Response(null, { status: 404 });
    });
    const visited: string[] = [];
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={["/pedidos"]}>
          <SiteHeader />
          <Routes>
            <Route path="/pedidos" element={<GuardedPage />} />
            <Route path="*" element={<Where visited={visited} />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await userEvent.click(
      await header().findByRole("button", { name: "Sair" }),
    );

    expect(await screen.findByTestId("where")).toHaveTextContent(/^\/$/);
    expect(visited).not.toContain("/entrar");
  });

  it("se o Cliente já estiver na página inicial quando o logout terminar, o logout termina e o cabeçalho atualiza", async () => {
    let finishLogout: () => void = () => {};
    let signedOut = false;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      if (init?.method === "DELETE") {
        await new Promise<void>((resolve) => {
          finishLogout = resolve;
        });
        signedOut = true;
        return new Response(null, { status: 204 });
      }
      if (String(input) === SESSION_URL)
        return signedOut
          ? new Response(null, { status: 401 })
          : new Response(JSON.stringify({ email: "ana@example.com" }), {
              status: 200,
              headers: { "Content-Type": "application/json" },
            });
      return new Response(null, { status: 404 });
    });
    renderHeader("/pedidos");

    await userEvent.click(
      await header().findByRole("button", { name: "Sair" }),
    );
    // While the request is in flight the visitor goes home by themselves.
    await userEvent.click(
      screen.getByRole("link", { name: "SapiensIA — página inicial" }),
    );
    finishLogout();

    expect(
      await header().findByRole("link", { name: "Entrar" }),
    ).toBeInTheDocument();
    expect(header().queryByRole("button", { name: /Sa/ })).toBeNull();
  });

  it("com a sessão já carregada, uma falha ao atualizá-la não mostra Entrar ao lado de Sair", async () => {
    let answer = 200;
    vi.spyOn(globalThis, "fetch").mockImplementation(async () =>
      answer === 200
        ? new Response(JSON.stringify({ email: "ana@example.com" }), {
            status: 200,
            headers: { "Content-Type": "application/json" },
          })
        : new Response(null, { status: 500 }),
    );
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <SiteHeader />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await header().findByRole("button", { name: "Sair" });

    answer = 500;
    await client.invalidateQueries({ queryKey: ["session"] });

    expect(
      await header().findByRole("button", { name: "Sair" }),
    ).toBeInTheDocument();
    expect(header().queryByRole("link", { name: "Entrar" })).toBeNull();
  });

  it("se a sessão falhar ao carregar, mostra Entrar e não Sair", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(null, { status: 500 }),
    );
    renderHeader();

    expect(
      await header().findByRole("link", { name: "Entrar" }),
    ).toHaveAttribute("href", "/entrar");
    expect(header().queryByRole("button", { name: "Sair" })).toBeNull();
  });

  it("se a sessão falhar em /entrar, não repete o link Entrar", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(null, { status: 500 }));
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
});

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, expect, test, vi } from "vitest";
import App from "./App";

afterEach(() => {
  vi.restoreAllMocks();
});

function renderAt(path: string) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

test("a página inicial sem sessão oferece Entrar e Criar conta", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(null, { status: 401 }),
  );
  renderAt("/");
  expect(
    await screen.findByRole("link", { name: "Entrar" }),
  ).toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "Funciona" }),
  ).not.toBeInTheDocument();
});

test("depois do login a página inicial nunca volta a mostrar o estado sem sessão", async () => {
  let loggedIn = false;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input);
    if (url === "/api/sessions" && init?.method === "POST") {
      loggedIn = true;
      return new Response(null, { status: 204 });
    }
    if (url === "/api/sessions/current") {
      return loggedIn
        ? new Response(JSON.stringify({ email: "ana@example.com" }), {
            status: 200,
            headers: { "Content-Type": "application/json" },
          })
        : new Response(null, { status: 401 });
    }
    return new Response(null, { status: 200 });
  });
  const user = userEvent.setup();
  renderAt("/");

  // Home is visited anonymously first, so the session cache holds "no session".
  await user.click(await screen.findByRole("link", { name: "Entrar" }));
  await user.type(screen.getByLabelText("E-mail"), "ana@example.com");
  await user.type(screen.getByLabelText("Senha"), "senha qualquer");

  let anonymousFlash = false;
  const observer = new MutationObserver(() => {
    if (document.querySelector('a[href="/entrar"]')) anonymousFlash = true;
  });
  observer.observe(document.body, { childList: true, subtree: true });
  await user.click(screen.getByRole("button", { name: "Entrar" }));

  expect(
    await screen.findByText("Você entrou como ana@example.com."),
  ).toBeInTheDocument();
  observer.disconnect();
  expect(anonymousFlash).toBe(false);
});

test("a rota /entrar mostra a tela de login", () => {
  renderAt("/entrar");
  expect(screen.getByRole("heading", { name: "Entrar" })).toBeInTheDocument();
});

test("a rota /painel/verificacao mostra a tela de verificação em duas etapas", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(JSON.stringify({ enrolled: true }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }),
  );
  renderAt("/painel/verificacao");
  expect(
    await screen.findByRole("heading", { name: "Verificação em duas etapas" }),
  ).toBeInTheDocument();
});

test.each([
  ["/termos-de-uso", "Termos de uso"],
  ["/privacidade", "Política de privacidade"],
  ["/reembolso", "Política de reembolso"],
])("a rota %s mostra o texto legal", (path, title) => {
  renderAt(path);
  expect(
    screen.getByRole("heading", { level: 1, name: title }),
  ).toBeInTheDocument();
});

test("o rodapé de informações legais aparece nas telas", () => {
  renderAt("/entrar");
  expect(
    screen.getByRole("navigation", { name: "Informações legais" }),
  ).toBeInTheDocument();
});

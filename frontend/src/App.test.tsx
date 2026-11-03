import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
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

test("a rota /entrar mostra a tela de login", () => {
  renderAt("/entrar");
  expect(screen.getByRole("heading", { name: "Entrar" })).toBeInTheDocument();
});

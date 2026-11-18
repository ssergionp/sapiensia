import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import { Button } from "@/components/ui/button";
import AdminGuard from "./AdminGuard";

afterEach(() => vi.restoreAllMocks());

describe("AdminGuard", () => {
  // jsdom does not apply Tailwind, so the real 44 px is measured in the browser (/verificar-tela);
  // here we check that the panel content sits under the rule that sets it.
  test("the panel content, buttons included, sits under the 44 px button rule", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          email: "admin@example.com",
          capabilities: ["PANEL_ACCESS"],
          oldCodeRequired: true,
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
    );
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={["/painel"]}>
          <AdminGuard>
            {() => (
              <main>
                <Button type="button">Salvar dados</Button>
              </main>
            )}
          </AdminGuard>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const button = await screen.findByRole("button", { name: "Salvar dados" });
    const wrapper = button.closest("div");
    expect(wrapper?.className).toContain("[&_[data-slot=button]]:h-11");
    expect(
      wrapper?.contains(screen.getByRole("navigation", { name: /painel/i })),
    ).toBe(true);
  });
});

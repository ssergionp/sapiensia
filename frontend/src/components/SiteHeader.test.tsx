import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { mockSystemTheme, resetTheme } from "@/test/systemTheme";
import SiteHeader from "./SiteHeader";

function renderHeader() {
  return render(
    <MemoryRouter>
      <SiteHeader />
    </MemoryRouter>,
  );
}

describe("SiteHeader", () => {
  beforeEach(() => {
    mockSystemTheme(false);
  });
  afterEach(resetTheme);

  it("mostra a marca com link para a página inicial", () => {
    renderHeader();
    expect(
      screen.getByRole("link", { name: "SapiensIA — página inicial" }),
    ).toHaveAttribute("href", "/");
  });

  it("tem o botão de trocar o tema", () => {
    renderHeader();
    expect(
      screen.getByRole("button", { name: "Usar tema escuro" }),
    ).toBeInTheDocument();
  });

  // #186 D2: navigation links move into the header in stage 2, together with the e2e selectors
  it("não repete os links de navegação das páginas", () => {
    renderHeader();
    const header = within(screen.getByRole("banner"));
    expect(header.getAllByRole("link")).toHaveLength(1);
    expect(header.queryByRole("link", { name: /Entrar/ })).toBeNull();
    expect(header.queryByRole("link", { name: /Pedidos/ })).toBeNull();
    expect(header.queryByRole("link", { name: /Catálogo/ })).toBeNull();
  });
});

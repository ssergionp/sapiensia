import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { expect, test } from "vitest";
import SiteFooter from "./SiteFooter";

function renderFooter() {
  return render(
    <MemoryRouter>
      <SiteFooter />
    </MemoryRouter>,
  );
}

test("o rodapé é um marco contentinfo com o nome Informações legais", () => {
  renderFooter();
  const footer = screen.getByRole("contentinfo");
  expect(
    within(footer).getByRole("navigation", { name: "Informações legais" }),
  ).toBeInTheDocument();
});

test("o rodapé tem os três links para os textos legais", () => {
  renderFooter();
  expect(screen.getByRole("link", { name: "Termos de uso" })).toHaveAttribute(
    "href",
    "/termos-de-uso",
  );
  expect(
    screen.getByRole("link", { name: "Política de privacidade" }),
  ).toHaveAttribute("href", "/privacidade");
  expect(
    screen.getByRole("link", { name: "Política de reembolso" }),
  ).toHaveAttribute("href", "/reembolso");
});

test("o rodapé mostra o canal de atendimento como mailto", () => {
  renderFooter();
  const link = screen.getByRole("link", {
    name: "Atendimento: suporte@sapiensia.app.br",
  });
  expect(link).toHaveAttribute("href", "mailto:suporte@sapiensia.app.br");
});

import { render, screen } from "@testing-library/react";
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

test("o rodapé é um marco contentinfo chamado Informações legais", () => {
  renderFooter();
  expect(
    screen.getByRole("contentinfo", { name: "Informações legais" }),
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

test("o rodapé mostra a frase da marca e o ano", () => {
  renderFooter();
  const footer = screen.getByRole("contentinfo", {
    name: "Informações legais",
  });
  expect(footer).toHaveTextContent("Conhecimento pronto para usar.");
  expect(footer).toHaveTextContent("© 2026 SapiensIA");
});

test("o rodapé mostra o canal de atendimento como mailto", () => {
  renderFooter();
  const link = screen.getByRole("link", {
    name: "Atendimento: suporte@sapiensia.app.br",
  });
  expect(link).toHaveAttribute("href", "mailto:suporte@sapiensia.app.br");
});

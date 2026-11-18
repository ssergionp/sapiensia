import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { expect, test, vi } from "vitest";
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
  expect(footer).toHaveTextContent(`© ${new Date().getFullYear()} SapiensIA`);
});

test("o ano do rodapé acompanha o ano corrente", () => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date(2027, 2, 1));
  try {
    renderFooter();
    expect(
      screen.getByRole("contentinfo", { name: "Informações legais" }),
    ).toHaveTextContent("© 2027 SapiensIA");
  } finally {
    vi.useRealTimers();
  }
});

test("o rodapé mostra o canal de atendimento como mailto", () => {
  renderFooter();
  const link = screen.getByRole("link", {
    name: "Atendimento: sapiensia.operacao@gmail.com",
  });
  expect(link).toHaveAttribute("href", "mailto:sapiensia.operacao@gmail.com");
});

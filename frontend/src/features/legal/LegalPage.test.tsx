import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { expect, test } from "vitest";
import LegalPage from "./LegalPage";
import {
  DRAFT_NOTICE,
  PRIVACY_POLICY,
  REFUND_POLICY,
  SUPPORT_EMAIL,
  TERMS_OF_USE,
} from "./legalTexts";

function renderPage(doc: typeof TERMS_OF_USE) {
  return render(
    <MemoryRouter>
      <LegalPage document={doc} />
    </MemoryRouter>,
  );
}

const DOCS = [
  ["Termos de uso", TERMS_OF_USE],
  ["Política de privacidade", PRIVACY_POLICY],
  ["Política de reembolso", REFUND_POLICY],
] as const;

test.each(DOCS)(
  "%s mostra um h1, o aviso de rascunho, a versão e o canal",
  (title, doc) => {
    renderPage(doc);
    expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
    expect(
      screen.getByRole("heading", { level: 1, name: title }),
    ).toBeInTheDocument();
    expect(screen.getByRole("note")).toHaveTextContent(
      "RASCUNHO — revisar com advogado. Este texto ainda não é a versão final e pode mudar.",
    );
    expect(DRAFT_NOTICE).toBe(
      "RASCUNHO — revisar com advogado. Este texto ainda não é a versão final e pode mudar.",
    );
    expect(
      screen.getByText("Versão de rascunho de 19/11/2026."),
    ).toBeInTheDocument();
    expect(
      screen.getAllByText(new RegExp(SUPPORT_EMAIL)).length,
    ).toBeGreaterThan(0);
    expect(document.title).toBe(`${title} | SapiensIA`);
  },
);

test.each(DOCS)(
  "%s tem seções com h2 e pontos em aberto marcados",
  (_title, doc) => {
    renderPage(doc);
    expect(
      screen.getAllByRole("heading", { level: 2 }).length,
    ).toBeGreaterThanOrEqual(5);
    expect(document.body.textContent).toContain("[a definir:");
  },
);

test("FR-023: a Política de reembolso cita 7 dias, defeito no arquivo e o Pix pelo Asaas", () => {
  renderPage(REFUND_POLICY);
  const text = document.body.textContent ?? "";
  expect(text).toContain("7 dias");
  expect(text).toMatch(/defeito/i);
  expect(text).toContain("Asaas");
  expect(text).toMatch(/Acesso (é|será) cancelado/);
});

test("FR-036: a Política de privacidade lista os direitos do titular e o canal", () => {
  renderPage(PRIVACY_POLICY);
  const text = document.body.textContent ?? "";
  for (const right of ["acesso", "correção", "exclusão", "exportação"]) {
    expect(text.toLowerCase()).toContain(right);
  }
  expect(text).toContain(SUPPORT_EMAIL);
  expect(text).toMatch(/anonimiza/i);
});

test("a Política de privacidade nomeia os operadores e os cookies essenciais", () => {
  renderPage(PRIVACY_POLICY);
  const text = document.body.textContent ?? "";
  for (const name of [
    "Supabase",
    "Fly.io",
    "Asaas",
    "Resend",
    "Cloudflare",
    "JSESSIONID",
    "XSRF-TOKEN",
    "SAPIENSIA_DEVICE",
  ]) {
    expect(text).toContain(name);
  }
});

test("a Política de privacidade guarda os registros por 5 anos sem chamar o prazo de provisório nem citar o contador", () => {
  renderPage(PRIVACY_POLICY);
  const text = document.body.textContent ?? "";
  expect(text).toContain("5 anos depois do Pedido");
  expect(text).not.toContain("provisório");
  expect(text).not.toContain("contador");
});

test("Termos e Privacidade não pedem mais nome, CPF e endereço do responsável", () => {
  renderPage(TERMS_OF_USE);
  const terms = document.body.textContent ?? "";
  expect(terms).not.toContain("[a definir: nome ou razão social");
  expect(terms).toContain("Responsável pelo site: uma pessoa física.");
  expect(terms).toContain(`Contato e reclamações: ${SUPPORT_EMAIL}.`);
  cleanup();

  renderPage(PRIVACY_POLICY);
  const privacy = document.body.textContent ?? "";
  expect(privacy).not.toContain("[a definir: nome ou razão social");
  expect(privacy).toContain(
    "(controlador): o responsável pelo site, uma pessoa física.",
  );
});

test("a seção Transferência internacional cita Resend, Cloudflare R2 e o art. 33, IX", () => {
  renderPage(PRIVACY_POLICY);
  const heading = screen.getByRole("heading", {
    level: 2,
    name: "Transferência internacional",
  });
  const section = heading.closest("section")?.textContent ?? "";
  expect(section).toContain("Resend");
  expect(section).toContain("Cloudflare R2");
  expect(section).toContain("art. 33, IX");
  expect(section).toContain("art. 7º, V");
  expect(section).not.toContain("[a definir: quais serviços");
});

test("os Termos de uso citam Pix de 30 minutos, Download de 60 segundos e limite de 10 por dia", () => {
  renderPage(TERMS_OF_USE);
  const text = document.body.textContent ?? "";
  expect(text).toContain("Pix");
  expect(text).toContain("60 segundos");
  expect(text).toContain("limite de 10 Downloads por dia");
  expect(text).toContain("30 minutos");
});

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, test } from "vitest";
import CoverImage from "./CoverImage";

describe("CoverImage", () => {
  test("mostra a imagem com alt, carga preguiçosa e proporção 2:3", () => {
    render(
      <CoverImage src="/api/products/p1/cover?v=abc" alt="Capa de Guia" />,
    );
    const img = screen.getByRole("img", { name: "Capa de Guia" });
    expect(img.getAttribute("src")).toBe("/api/products/p1/cover?v=abc");
    expect(img.getAttribute("loading")).toBe("lazy");
    expect(img.getAttribute("decoding")).toBe("async");
    expect(img.className).toContain("object-contain");
    expect(img.className).toContain("aspect-[2/3]");
  });

  test("sem src mostra o quadro neutro escondido, sem img e sem texto", () => {
    const { container } = render(<CoverImage src={null} alt="Capa de Guia" />);
    expect(screen.queryByRole("img")).toBeNull();
    const frame = container.firstElementChild as HTMLElement;
    expect(frame.getAttribute("aria-hidden")).toBe("true");
    expect(frame.className).toContain("bg-muted");
    expect(frame.textContent).toBe("");
  });

  test("sem imagem, mostra a cor da categoria e o título só como enfeite", () => {
    const { container } = render(
      <CoverImage
        src={null}
        alt="Capa de Guia"
        category="FINANCE"
        title="Guia de finanças"
      />,
    );
    const frame = container.firstElementChild as HTMLElement;
    expect(frame.getAttribute("aria-hidden")).toBe("true");
    expect(frame.dataset.category).toBe("FINANCE");
    expect(frame.style.backgroundColor).toBe("var(--cover-finance)");
    // drawn by CSS from data-title, so screen readers and text queries never see a second title
    expect(frame.dataset.title).toBe("Guia de finanças");
    expect(frame.textContent).toBe("");
  });

  test("com título e sem categoria (Biblioteca), usa o fundo escuro neutro com o título", () => {
    const { container } = render(
      <CoverImage src={null} alt="" title="Guia de finanças" />,
    );
    const frame = container.firstElementChild as HTMLElement;
    expect(frame.style.backgroundColor).toBe("var(--cover-other)");
    expect(frame.dataset.title).toBe("Guia de finanças");
  });

  test("a capa real não ganha a cor de reserva (sem faixas escuras em capa fora de 2:3)", () => {
    render(
      <CoverImage
        src="/api/products/p1/cover?v=abc"
        alt="Capa de Guia"
        category="LAW"
        title="Guia"
      />,
    );
    expect(screen.getByRole("img").style.backgroundColor).toBe("");
  });

  test("com falha ao carregar, troca pela cor da categoria", () => {
    const { container } = render(
      <CoverImage
        src="/api/products/p1/cover?v=abc"
        alt="Capa de Guia"
        category="LAW"
        title="Guia"
      />,
    );
    fireEvent.error(screen.getByRole("img"));
    const frame = container.firstElementChild as HTMLElement;
    expect(frame.style.backgroundColor).toBe("var(--cover-law)");
  });

  test("se a imagem falhar, troca pelo quadro neutro", () => {
    const { container } = render(
      <CoverImage src="/api/products/p1/cover?v=abc" alt="Capa de Guia" />,
    );
    fireEvent.error(screen.getByRole("img"));
    expect(screen.queryByRole("img")).toBeNull();
    expect(container.firstElementChild?.getAttribute("aria-hidden")).toBe(
      "true",
    );
  });

  describe("título longo na capa de reserva", () => {
    function titleSize(title: string): number {
      const { container } = render(
        <CoverImage src={null} alt="" category="LAW" title={title} />,
      );
      const frame = container.firstElementChild as HTMLElement;
      const value = frame.style.getPropertyValue("--cover-title-size");
      // "min(10cqw, 9.1cqw)" → the smaller number is the one that applies
      const numbers = [...value.matchAll(/([\d.]+)cqw/g)].map((m) =>
        Number(m[1]),
      );
      expect(numbers.length).toBeGreaterThan(0);
      return Math.min(...numbers);
    }

    test("não corta o título com reticências e escala a fonte pela largura da capa", () => {
      const { container } = render(
        <CoverImage src={null} alt="" category="LAW" title={"A".repeat(120)} />,
      );
      const frame = container.firstElementChild as HTMLElement;
      expect(frame.className).not.toContain("line-clamp");
      expect(frame.className).toContain("@container");
    });

    test("o título de 120 caracteres usa fonte menor que o curto, e o curto não passa do teto", () => {
      const short = titleSize("Guia");
      const longest = titleSize("palavra ".repeat(15).trim());
      expect(longest).toBeLessThan(short);
      expect(short).toBeLessThanOrEqual(10);
    });
  });

  test("o fundo e o texto da capa vêm de tokens, sem cor escrita no código", () => {
    const { container } = render(
      <CoverImage src={null} alt="" category="HEALTH" title="Guia" />,
    );
    const frame = container.firstElementChild as HTMLElement;
    expect(frame.outerHTML).not.toMatch(/#[0-9a-f]{3,6}/i);
    expect(frame.className).toContain("text-cover-foreground");
  });
});

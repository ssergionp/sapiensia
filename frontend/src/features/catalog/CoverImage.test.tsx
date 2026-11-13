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
});

import { render, screen } from "@testing-library/react";
import { describe, expect, test } from "vitest";
import StatusBadge from "./StatusBadge";

describe("StatusBadge", () => {
  test("shows the status text and carries data-status", () => {
    render(
      <StatusBadge status="PAID" tone="success">
        Pago
      </StatusBadge>,
    );

    expect(screen.getByText("Pago")).toHaveAttribute("data-status", "PAID");
  });

  test("the success tone uses the status tokens, not fixed colors", () => {
    render(
      <StatusBadge status="PUBLISHED" tone="success">
        Publicado
      </StatusBadge>,
    );

    const badge = screen.getByText("Publicado");
    expect(badge).toHaveClass("bg-success", "text-success-foreground");
    expect(badge.className).not.toMatch(/#[0-9a-f]{6}/i);
  });

  test("the outline tone has a border, so it differs from the neutral one without color", () => {
    render(
      <StatusBadge status="UNPUBLISHED" tone="outline">
        Despublicado
      </StatusBadge>,
    );

    expect(screen.getByText("Despublicado")).toHaveClass("border");
  });
});

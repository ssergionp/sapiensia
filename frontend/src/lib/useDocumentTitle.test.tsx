import { render } from "@testing-library/react";
import { describe, expect, test } from "vitest";
import { DEFAULT_TITLE, useDocumentTitle } from "./useDocumentTitle";

function Page({ title }: { title: string }) {
  useDocumentTitle(title);
  return null;
}

describe("useDocumentTitle", () => {
  test("sets the document title", () => {
    render(<Page title="Minha página | SapiensIA" />);

    expect(document.title).toBe("Minha página | SapiensIA");
  });

  test("restores the default title on unmount", () => {
    const { unmount } = render(<Page title="Minha página | SapiensIA" />);
    unmount();

    expect(DEFAULT_TITLE).toBe("SapiensIA");
    expect(document.title).toBe("SapiensIA");
  });

  test("updates the title when the argument changes", () => {
    const { rerender } = render(<Page title="Um" />);
    rerender(<Page title="Dois" />);

    expect(document.title).toBe("Dois");
  });
});

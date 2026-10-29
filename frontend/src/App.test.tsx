import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import App from "./App";

test("mostra o título do projeto", () => {
  render(
    <MemoryRouter>
      <App />
    </MemoryRouter>,
  );
  expect(
    screen.getByRole("heading", { name: "sapiensia" }),
  ).toBeInTheDocument();
});

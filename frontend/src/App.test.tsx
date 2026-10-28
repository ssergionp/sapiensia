import { render, screen } from "@testing-library/react";
import App from "./App";

test("mostra o título do projeto", () => {
  render(<App />);
  expect(
    screen.getByRole("heading", { name: "nome-do-projeto" }),
  ).toBeInTheDocument();
});

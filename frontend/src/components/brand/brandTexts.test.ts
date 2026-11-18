import { describe, expect, it } from "vitest";
import { BRAND_TEXTS, splitTagline } from "./brandTexts";

describe("splitTagline", () => {
  it("destaca as duas últimas palavras da frase da marca de hoje", () => {
    expect(splitTagline(BRAND_TEXTS.tagline)).toEqual([
      "Conhecimento pronto ",
      "para usar.",
    ]);
  });

  it("funciona com outra frase, sem depender de 'para usar.'", () => {
    expect(splitTagline("Aprenda no seu ritmo agora")).toEqual([
      "Aprenda no seu ",
      "ritmo agora",
    ]);
  });

  it("frase de uma ou duas palavras fica inteira, sem destaque e sem erro", () => {
    expect(splitTagline("Conhecimento")).toEqual(["Conhecimento", ""]);
    expect(splitTagline("Conhecimento pronto")).toEqual([
      "Conhecimento pronto",
      "",
    ]);
  });
});

describe("splitTagline com espaços sobrando", () => {
  it("ignora espaço duplo e espaço no fim", () => {
    expect(splitTagline("Conteúdo pronto  para usar. ")).toEqual([
      "Conteúdo pronto ",
      "para usar.",
    ]);
  });
});

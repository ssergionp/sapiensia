import type { ProductCategory } from "./schemas";

export const CATEGORY_LABELS: Record<ProductCategory, string> = {
  HEALTH: "Saúde",
  LAW: "Direito",
  FINANCE: "Finanças",
  SECURITY: "Segurança",
  EDUCATION: "Educação",
  TECHNOLOGY: "Tecnologia",
  BUSINESS: "Negócios",
  OTHER: "Outros",
};

export const NO_PERMISSION_PRODUCTS =
  "Você não tem permissão para gerenciar Produtos.";
export const NO_PERMISSION_PRICE =
  "Você não tem permissão para alterar o preço.";
export const UNAVAILABLE_LOAD =
  "O sistema está indisponível no momento. Tente novamente em alguns instantes.";

export const SAVE_ERRORS = {
  conflict:
    "Este Produto foi alterado em outra janela. O que você digitou não foi salvo. Use Recarregar para ver a versão atual.",
  forbidden: "Você não tem permissão para fazer esta alteração.",
  unavailable:
    "O sistema está indisponível no momento e nada foi salvo. Tente novamente em alguns instantes.",
  unexpected: "Não foi possível salvar agora. Tente novamente.",
} as const;

// Backend validation code to the same message the form shows client side.
export const SERVER_FIELD_MESSAGES: Record<string, string> = {
  TITLE_REQUIRED: "Digite o título.",
  TITLE_TOO_LONG: "O título pode ter até 120 caracteres.",
  TITLE_INVALID: "O título tem caracteres que não podem ser usados.",
  DESCRIPTION_REQUIRED: "Digite a descrição.",
  DESCRIPTION_TOO_LONG: "A descrição pode ter até 5.000 caracteres.",
  DESCRIPTION_INVALID: "A descrição tem caracteres que não podem ser usados.",
  CATEGORY_REQUIRED: "Selecione uma categoria.",
  PRICE_REQUIRED: "Digite o preço.",
  PRICE_TOO_LOW: "O preço mínimo é R$ 5,00.",
  PRICE_TOO_HIGH: "O preço máximo é R$ 9.999,99.",
};

// Server field name to form field name.
export const SERVER_FIELD_NAMES: Record<string, string> = {
  title: "title",
  description: "description",
  category: "category",
  priceCents: "price",
};

export const PRODUCT_NOT_FOUND = "Produto não encontrado.";

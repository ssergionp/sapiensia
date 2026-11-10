import type { ProductCategory, ProductFileRejectionCode } from "./schemas";

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

export const FILE_MESSAGES: Record<ProductFileRejectionCode, string> = {
  FILE_REQUIRED: "Selecione um arquivo PDF.",
  FILE_EMPTY: "O arquivo está vazio.",
  FILE_TOO_LARGE: "O arquivo passa de 50 MB. Envie um PDF menor.",
  FILE_NOT_PDF: "O arquivo não é um PDF. Envie um arquivo PDF.",
  FILE_INVALID:
    "Não foi possível ler este PDF. Ele pode estar corrompido. Gere o PDF de novo e tente outra vez.",
  FILE_ENCRYPTED:
    "O PDF está protegido por senha. Envie uma versão sem proteção.",
  FILE_TOO_MANY_PAGES:
    "O PDF passa de 1.000 páginas. Envie um arquivo com até 1.000 páginas.",
};

export const FILE_UPLOAD_ERRORS = {
  forbidden: "Você não tem permissão para enviar o arquivo do Produto.",
  unavailable:
    "O sistema está indisponível no momento e o arquivo não foi enviado. Tente novamente em alguns instantes.",
  unexpected:
    "Não foi possível enviar o arquivo. Verifique a conexão e tente novamente.",
} as const;

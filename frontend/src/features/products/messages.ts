import type {
  ProductCategory,
  ProductCoverRejectionCode,
  ProductFileRejectionCode,
  ProductStatus,
} from "./schemas";

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

import { TOO_MANY_REQUESTS } from "@/lib/tooManyRequests";

export const REAUTHENTICATION_TEXTS = {
  publishedNotice:
    "Este Produto já foi publicado. Para trocar o arquivo, confirme sua senha e o código do aplicativo autenticador. Quem já comprou passa a baixar a nova versão.",
  title: "Confirme sua identidade",
  passwordLabel: "Senha",
  codeLabel: "Código do aplicativo autenticador",
  confirm: "Confirmar e enviar",
  confirming: "Confirmando…",
  cancel: "Cancelar",
  rejected: "Senha ou código incorretos. Confira e tente de novo.",
  locked: TOO_MANY_REQUESTS,
  expired:
    "A confirmação venceu. Digite sua senha e o código de novo para enviar o arquivo.",
} as const;

export const STATUS_LABELS: Record<ProductStatus, string> = {
  DRAFT: "Rascunho",
  PUBLISHED: "Publicado",
  UNPUBLISHED: "Despublicado",
};

export const STATUS_NOTICES: Record<ProductStatus, string> = {
  DRAFT:
    "Este Produto está em rascunho: os clientes ainda não conseguem vê-lo nem comprá-lo.",
  PUBLISHED:
    "Este Produto está publicado: aparece no Catálogo e pode ser comprado.",
  UNPUBLISHED:
    "Este Produto está despublicado: saiu do Catálogo, e quem já comprou continua com acesso.",
};

export const PUBLICATION_MESSAGES = {
  heading: "Publicação",
  publish: "Publicar",
  publishAgain: "Publicar de novo",
  unpublish: "Despublicar",
  needFile: "Envie o Arquivo do produto para poder publicar.",
  confirmQuestion:
    "Publicar este Produto? Ele vai aparecer no Catálogo. Depois da primeira publicação, trocar o arquivo vai exigir sua senha e o código do aplicativo autenticador.",
  confirm: "Confirmar publicação",
  cancel: "Cancelar",
  keepsAccess: "Quem já comprou continua com acesso.",
  publishing: "Publicando…",
  unpublishing: "Despublicando…",
  published: "Produto publicado.",
  unpublished: "Produto despublicado.",
} as const;

export const PUBLICATION_ERRORS = {
  fileRequired: "Envie o Arquivo do produto antes de publicar.",
  forbidden:
    "Você não tem permissão para publicar ou despublicar este Produto.",
  unavailable:
    "O sistema está indisponível no momento e nada foi alterado. Tente novamente em alguns instantes.",
  unexpected: "Não foi possível alterar a publicação agora. Tente novamente.",
} as const;

export const COVER_TEXTS = {
  title: "Capa do produto",
  label: "Imagem da capa",
  help: "PNG, JPEG ou WebP, até 2 MB e até 4.000 pixels de largura e de altura. Recomendado: 1.200 × 1.800 pixels.",
  helpReplace:
    "Enviar outra imagem substitui a atual. Os dados da foto (como local e aparelho) são removidos. Se a foto aparecer girada, gire-a em um editor e envie de novo.",
  none: "Nenhuma capa enviada.",
  alt: (title: string) => `Capa de ${title}`,
  send: "Enviar capa",
  sending: "Enviando capa…",
  sent: "Capa enviada.",
  remove: "Remover capa",
  confirmQuestion:
    "Remover a capa deste Produto? O Catálogo passa a mostrar um quadro sem imagem.",
  confirmRemove: "Confirmar remoção",
  removing: "Removendo capa…",
  cancel: "Cancelar",
  removed: "Capa removida.",
  current: (dimensions: string, size: string, uploadedAt: string) =>
    `Capa atual: ${dimensions}, ${size}, enviada em ${uploadedAt}.`,
} as const;

export const COVER_MESSAGES: Record<ProductCoverRejectionCode, string> = {
  COVER_REQUIRED: "Selecione uma imagem.",
  COVER_EMPTY: "A imagem está vazia.",
  COVER_TOO_LARGE:
    "A imagem passa de 2 MB. Envie uma imagem menor, de preferência em JPEG ou WebP.",
  COVER_NOT_IMAGE:
    "A imagem precisa ser PNG, JPEG ou WebP. SVG e GIF não são aceitos.",
  COVER_INVALID:
    "Não foi possível ler esta imagem. Ela pode estar corrompida. Exporte a imagem de novo e tente outra vez.",
  COVER_TOO_MANY_PIXELS:
    "A imagem passa de 4.000 pixels de largura ou de altura. Reduza a imagem e envie de novo.",
  COVER_ANIMATED: "Imagens animadas não são aceitas. Envie uma imagem parada.",
};

export const COVER_ERRORS = {
  forbidden: "Você não tem permissão para alterar a capa do Produto.",
  unavailable:
    "O sistema está indisponível no momento e a capa não foi alterada. Tente novamente em alguns instantes.",
  unexpected:
    "Não foi possível alterar a capa. Verifique a conexão e tente novamente.",
} as const;

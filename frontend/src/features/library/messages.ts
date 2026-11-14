// Single source of the Library screen texts.
export const LIBRARY_TEXTS = {
  documentTitle: "Biblioteca | SapiensIA",
  heading: "Biblioteca",
  loading: "Carregando…",
  grantedOn: (date: string) => `Comprado em ${date}`,
  empty: "Você ainda não tem Produtos na Biblioteca.",
  goToCatalog: "Ver o Catálogo",
  loadError: "Não foi possível carregar a Biblioteca. Tente novamente.",
  retry: "Tentar novamente",
} as const;

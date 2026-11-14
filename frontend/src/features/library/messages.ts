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
  download: "Baixar",
  downloadLabel: (title: string) => `Baixar ${title}`,
  downloadPreparing: "Preparando…",
  downloadStarted:
    "Download iniciado. Se não começar, clique em Baixar de novo.",
  downloadError: "Não foi possível preparar o download. Tente novamente.",
  downloadUnavailable:
    "Este Produto não está mais disponível na sua Biblioteca.",
} as const;

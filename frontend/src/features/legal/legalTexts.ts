// Single source of the legal texts (#23): Terms of use, Privacy policy and Refund policy as data, plus the footer texts.
// All of it is a DRAFT for a lawyer to review (ADR-0007 launch gate). Open points are written inline as "[a definir: ...]".
// Anything the personal-data inventory marks as "a confirmar" or "a conferir" is worded here as proposed or provisional.

export const SUPPORT_EMAIL = "sapiensia.operacao@gmail.com";

export const DRAFT_NOTICE =
  "RASCUNHO — revisar com advogado. Este texto ainda não é a versão final e pode mudar.";

export const DRAFT_VERSION = "Versão de rascunho de 19/11/2026.";

export const LEGAL_TEXTS = {
  footerLabel: "Informações legais",
  support: `Atendimento: ${SUPPORT_EMAIL}`,
  documentTitle: (title: string) => `${title} | SapiensIA`,
} as const;

export type LegalSection = {
  heading: string;
  paragraphs?: readonly string[];
  items?: readonly string[];
};

export type LegalDocument = {
  path: string;
  title: string;
  sections: readonly LegalSection[];
};

export const TERMS_OF_USE: LegalDocument = {
  path: "/termos-de-uso",
  title: "Termos de uso",
  sections: [
    {
      heading: "Quem somos",
      paragraphs: [
        "A SapiensIA é uma loja de conteúdos digitais em PDF (e-books, cartilhas, tutoriais e checklists). Estes Termos valem para quem cria uma conta e compra ou baixa um Produto.",
        `Responsável pelo site: uma pessoa física. Contato e reclamações: ${SUPPORT_EMAIL}.`,
      ],
    },
    {
      heading: "Conta",
      paragraphs: [
        "Para comprar e baixar é preciso ter uma conta, criada com e-mail e senha e confirmada pelo e-mail. Não há compra nem Download como visitante.",
        "Você é responsável por manter a senha em segredo e por tudo o que for feito na sua conta. Avise o atendimento se achar que alguém entrou nela sem a sua autorização.",
        "[a definir: idade mínima para criar conta e comprar, e o que fazer com menores de idade].",
      ],
    },
    {
      heading: "Compra e pagamento",
      paragraphs: [
        "A compra é feita só por Pix, processado pelo Asaas. Cada compra gera um Pedido com um código Pix para pagar.",
        "O valor cobrado é o preço do Produto calculado pelo servidor no momento do Pedido, em reais. Mudar o preço de um Produto afeta só os Pedidos feitos depois da mudança.",
        "O código Pix de um Pedido vale por 30 minutos; depois disso o Pedido expira e é preciso fazer um novo. O Acesso ao Produto é liberado depois que o Asaas confirma o pagamento.",
      ],
    },
    {
      heading: "Acesso e Download",
      paragraphs: [
        "Depois do pagamento, você tem Acesso vitalício ao Arquivo do produto, sempre na versão mais recente. [a definir: o advogado deve rever a expressão “Acesso vitalício”, por exemplo trocando por “enquanto a SapiensIA estiver em operação”].",
        "Cada Download usa um link que vale 60 segundos e é gerado a cada clique, depois de conferir o seu login e o seu Acesso. Há um limite de 10 Downloads por dia, por Produto. Registramos o Cliente, o Produto, a versão do arquivo e a data de cada Download.",
        "Se um Produto sair do Catálogo, o seu Acesso continua. Só um Reembolso cancela o Acesso; quando você pede o Reembolso, o Acesso daquele Pedido fica suspenso até a devolução do valor.",
      ],
    },
    {
      heading: "Uso permitido",
      paragraphs: [
        "O Produto é para o seu uso pessoal. Você não pode vender, publicar, nem compartilhar o arquivo ou o link de Download com outras pessoas.",
        "[a definir: redação final da cláusula de uso pessoal e proibição de redistribuição, e as consequências de descumpri-la].",
      ],
    },
    {
      heading: "Reembolso",
      paragraphs: [
        "O pedido de Reembolso, os prazos e a forma de devolução estão na Política de reembolso, que faz parte destes Termos.",
      ],
    },
    {
      heading: "Suspensão por abuso",
      paragraphs: [
        "Podemos limitar ou suspender uma conta que use o site de forma abusiva, tente burlar os limites de Download, tente fraudar pagamentos, redistribua os Produtos ou peça Reembolsos repetidos para ficar com os arquivos sem pagar. [a definir: critérios e aviso prévio antes da suspensão].",
      ],
    },
    {
      heading: "Mudanças nestes Termos",
      paragraphs: [
        "Podemos mudar estes Termos. [a definir: como e com que antecedência os Clientes serão avisados e a partir de quando a mudança vale].",
      ],
    },
    {
      heading: "Lei e foro",
      paragraphs: [
        "Estes Termos seguem a lei brasileira. [a definir: foro competente, respeitando o foro do consumidor].",
      ],
    },
    {
      heading: "Contato",
      paragraphs: [
        `Dúvidas, pedidos e reclamações: ${SUPPORT_EMAIL}. [a definir: prazo de resposta do atendimento].`,
      ],
    },
  ],
};

export const PRIVACY_POLICY: LegalDocument = {
  path: "/privacidade",
  title: "Política de privacidade",
  sections: [
    {
      heading: "Quem é o responsável pelos dados",
      paragraphs: [
        "Responsável pelo tratamento dos dados pessoais (controlador): o responsável pelo site, uma pessoa física.",
        `Canal para falar sobre os seus dados: ${SUPPORT_EMAIL}.`,
      ],
    },
    {
      heading: "Quais dados tratamos",
      items: [
        "E-mail e senha da conta (a senha é guardada só em forma de hash, nunca em texto) e as datas da conta.",
        "Aparelhos conhecidos: um identificador do aparelho (guardado em hash) e datas, para avisar quando você entra de um aparelho novo.",
        "Pedidos e Acessos: Produto, valor, datas e estado.",
        "Solicitações de Reembolso: o motivo escolhido, o comentário que você escrever (opcional) e a data.",
        "Eventos de pagamento recebidos do Asaas, como chegaram, para provar o pagamento.",
        "Registros de download: Cliente, Produto, versão do arquivo e data. Não guardamos o seu IP nos Registros de download.",
        "Trilha de auditoria das ações do Administrador no painel, que inclui o IP de origem dessas ações. Ela não leva o e-mail do Cliente.",
        "Para a cobrança Pix, o Asaas recebe o seu e-mail (usado como nome do cliente) e o seu CPF. O CPF não fica no nosso banco.",
        "Para frear tentativas de login, o sistema guarda o seu IP e uma versão embaralhada (hash) do e-mail só na memória do servidor, por cerca de 15 minutos. Nada disso vai para o banco. Base legal proposta: legítimo interesse (segurança).",
        "Não tratamos dados pessoais sensíveis nem fazemos perfis para publicidade.",
      ],
    },
    {
      heading: "Para que usamos e em que base legal",
      paragraphs: [
        "As bases legais abaixo são uma proposta, a confirmar com o advogado.",
      ],
      items: [
        "Entrar na conta, vender e entregar o Produto, enviar e-mails da conta e da compra: execução de contrato.",
        "Segurança (avisar sobre aparelho novo, limitar tentativas de login, auditoria): legítimo interesse.",
        "Guardar o registro financeiro dos Pedidos: obrigação legal.",
        "[a definir: confirmação final das bases legais pelo advogado].",
      ],
    },
    {
      heading: "Com quem compartilhamos (operadores)",
      items: [
        "Supabase: banco de dados e arquivos dos Produtos (região de São Paulo).",
        "Fly.io: hospedagem do site (região de São Paulo).",
        "Asaas: processamento do pagamento por Pix.",
        "Resend: envio dos e-mails (confirmação, nova senha, aparelho novo, pagamento confirmado, reembolso).",
        "Cloudflare R2: cópia de segurança diária do banco.",
        "Não vendemos dados pessoais.",
      ],
    },
    {
      heading: "Transferência internacional",
      paragraphs: [
        "Dois desses serviços tratam dados fora do Brasil: o Resend, que envia os e-mails, e o Cloudflare R2, onde fica a cópia de segurança do banco. Essa transferência é necessária para executar o contrato com você (Lei 13.709/2018, art. 33, IX, com o art. 7º, V).",
      ],
    },
    {
      heading: "Por quanto tempo guardamos",
      items: [
        "Conta: enquanto ela existir. Conta que nunca foi confirmada é apagada em 7 dias.",
        "Códigos de confirmação de e-mail: 24 horas. Códigos de nova senha: 30 minutos.",
        "Pedidos, Acessos, eventos de pagamento, solicitações de Reembolso e Registros de download: 5 anos depois do Pedido.",
        "Trilha de auditoria: 365 dias.",
        "O prazo de guarda de cada serviço de fora (Asaas, Resend, Fly.io) ainda está a conferir: [a definir: prazos de cada operador].",
      ],
    },
    {
      heading: "Cookies",
      paragraphs: [
        "Usamos apenas cookies essenciais para o site funcionar. Não usamos cookies de análise nem de publicidade.",
      ],
      items: [
        "JSESSIONID: mantém a sua sessão depois do login.",
        "XSRF-TOKEN: proteção contra requisições forjadas (CSRF).",
        "SAPIENSIA_DEVICE: lembra que o aparelho já é conhecido, para o aviso de aparelho novo.",
        "[a definir: se o advogado exige aviso ou banner de cookies para estes cookies essenciais].",
      ],
    },
    {
      heading: "Seus direitos",
      paragraphs: [
        `Você pode pedir, pelo e-mail ${SUPPORT_EMAIL}, o acesso aos seus dados, a correção de dados errados, a exportação (cópia) dos seus dados e a exclusão da sua conta. Hoje esses pedidos são atendidos à mão, por e-mail. [a definir: prazo de resposta; proposta provisória: declaração completa de acesso em até 15 dias, Lei 13.709/2018, art. 19, II].`,
        "A exclusão da conta é feita por anonimização: o e-mail e a senha são substituídos e as sessões encerradas. Os registros financeiros (Pedidos, Acessos, eventos de pagamento, solicitações de Reembolso e Registros de download) ficam pelo prazo de guarda, sem o seu e-mail nem o seu nome. O comentário que você escreveu numa solicitação de Reembolso é apagado quando a conta é anonimizada.",
        "O Asaas guarda o e-mail e o CPF da cobrança por regra própria; a anonimização não apaga esses dados lá.",
      ],
    },
    {
      heading: "Segurança",
      paragraphs: [
        "Usamos conexão segura, senhas guardadas em hash, aviso de aparelho novo, limite de tentativas de login, links de Download de 60 segundos e acesso restrito ao painel com verificação em duas etapas. Nenhum sistema é totalmente livre de risco; se houver um incidente que afete os seus dados, avisaremos conforme a lei. [a definir: prazo e forma do aviso de incidente].",
      ],
    },
    {
      heading: "Encarregado de dados",
      paragraphs: [
        "[a definir: nome e contato do encarregado, ou a dispensa para agente de tratamento de pequeno porte, a confirmar com o advogado].",
      ],
    },
    {
      heading: "Mudanças nesta Política",
      paragraphs: [
        "Esta Política pode mudar. [a definir: como os Clientes serão avisados de mudanças relevantes].",
      ],
    },
  ],
};

export const REFUND_POLICY: LegalDocument = {
  path: "/reembolso",
  title: "Política de reembolso",
  sections: [
    {
      heading: "Quando você pode pedir",
      items: [
        "Em até 7 dias depois do pagamento, contados até o fim do 7º dia (direito de arrependimento, art. 49 do Código de Defesa do Consumidor). Dentro desse prazo o Reembolso é sempre concedido, qualquer que seja o motivo, mesmo que você já tenha baixado o arquivo.",
        "Por defeito no arquivo, mesmo depois dos 7 dias: o PDF não abre, está corrompido ou não é o Produto comprado.",
      ],
    },
    {
      heading: "Como pedir",
      paragraphs: [
        "Em até 7 dias depois do pagamento: entre na sua conta, abra Meus Pedidos e escolha “Pedir Reembolso” no Pedido pago. Você escolhe o motivo e, se quiser, escreve um comentário. O motivo nos ajuda a melhorar e não muda o seu direito ao Reembolso.",
        `Por defeito no arquivo depois dos 7 dias, ou se não conseguir pedir pelo site: escreva para ${SUPPORT_EMAIL} com o e-mail da sua conta, o Produto e o motivo. [a definir: prazo de resposta ao pedido por e-mail; proposta provisória: primeira resposta em até 2 dias úteis].`,
      ],
    },
    {
      heading: "Como o dinheiro volta",
      paragraphs: [
        "O Reembolso é feito pelo Pix, de volta a quem pagou, por meio do Asaas, em até 2 dias úteis depois do pedido. O prazo para o valor aparecer na sua conta depende do banco.",
      ],
    },
    {
      heading: "O que acontece com o Acesso",
      paragraphs: [
        "Quando você pede o Reembolso, o Acesso daquele Pedido é suspenso na hora: o Produto sai da sua Biblioteca e você não pode mais baixar o arquivo.",
        "Quando o Asaas mostra que a cobrança foi estornada, o Acesso é cancelado em definitivo e você recebe um e-mail.",
        "Depois do cancelamento você pode comprar o Produto de novo e recebe um Acesso novo.",
      ],
    },
    {
      heading: "Reembolsos repetidos",
      paragraphs: [
        "Pedir Reembolso não impede novas compras. Reembolsos repetidos podem ser analisados como uso abusivo, conforme a seção “Suspensão por abuso” dos Termos de uso.",
      ],
    },
    {
      heading: "Casos especiais",
      paragraphs: [
        "Um estorno parcial ou uma contestação do Pix (MED) ficam registrados para revisão, e o Acesso só muda quando houver o estorno da cobrança. [a definir: tratamento de contestações e fraude].",
      ],
    },
  ],
};

export const LEGAL_DOCUMENTS = [
  TERMS_OF_USE,
  PRIVACY_POLICY,
  REFUND_POLICY,
] as const;

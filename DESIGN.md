---
name: SapiensIA
description: Conhecimento pronto para usar.
colors:
  pedra: "#E9E8E2"
  papiro: "#FAF6F0"
  carvao: "#121615"
  pinho: "#114B3E"
  terracota: "#F08A4B"
  noite-verde: "#0D110F"
  pedra-funda: "#DEDCD4"
  traco-claro: "#D3D0C6"
  traco-escuro: "#26302C"
  musgo-escuro: "#1A201D"
  texto-secundario: "#4F5A55"
  texto-secundario-escuro: "#A9B0AC"
  borda-campo: "#737973"
  borda-campo-escuro: "#6B7570"
  erro: "#B42318"
  erro-escuro: "#FF8A7A"
  capa-financas: "#114B3E"
  capa-financas-alt: "#133B4D"
  capa-direito: "#8C3B20"
  capa-seguranca: "#182026"
  capa-saude: "#1F5C4D"
  capa-educacao: "#16264C"
typography:
  display:
    fontFamily: "Fraunces Variable, Georgia, serif"
    fontWeight: 600
    letterSpacing: "-0.025em"
  headline:
    fontFamily: "Fraunces Variable, Georgia, serif"
    fontWeight: 600
    letterSpacing: "-0.025em"
  body:
    fontFamily: "Plus Jakarta Sans Variable, system-ui, sans-serif"
    fontWeight: 500
  label:
    fontFamily: "Plus Jakarta Sans Variable, system-ui, sans-serif"
    fontWeight: 600
  price:
    fontFamily: "Plus Jakarta Sans Variable, system-ui, sans-serif"
    fontWeight: 700
    fontFeature: "tnum"
rounded:
  sm: "6px"
  md: "8px"
  lg: "10px"
  xl: "14px"
components:
  button-primary:
    backgroundColor: "{colors.pinho}"
    textColor: "{colors.papiro}"
    rounded: "{rounded.md}"
  button-primary-dark:
    backgroundColor: "{colors.terracota}"
    textColor: "{colors.carvao}"
    rounded: "{rounded.md}"
  card:
    backgroundColor: "{colors.papiro}"
    textColor: "{colors.carvao}"
    rounded: "{rounded.xl}"
  card-dark:
    backgroundColor: "{colors.carvao}"
    textColor: "{colors.papiro}"
    rounded: "{rounded.xl}"
---

# Design System: SapiensIA

Fonte da verdade dos valores: `frontend/src/index.css` (`:root` e `.dark`), conferidos por `frontend/src/theme-tokens.test.ts`. Direção definida pelo Sérgio na issue #186 (17/11/2026).

## Overview

**Creative North Star: "A estante de pinho"**

Materiais de estudo vendidos como objetos bem feitos: papel claro sobre pedra, tinta de carvão, madeira escura nas ações e um ponto de terracota que marca o que importa. A tipografia editorial (Fraunces) dá voz aos títulos; a interface (Plus Jakarta Sans) fica quieta e precisa. A capa de cada Produto é a imagem principal da página; o resto da interface não compete com ela.

No escuro, a estante vira noite: fundo quase preto com subtom verde, superfícies de carvão, texto de papiro e a terracota assume as ações.

**Key Characteristics:**
- Duas famílias, papéis separados: Fraunces só em títulos; Plus Jakarta Sans em todo o resto.
- Uma cor de ação por tema (Pinho no claro, Terracota no escuro).
- Terracota é tempero: marcador, traço, ponto do logo.
- Contraste AA em todo texto; AAA no texto dos botões de ação.

## Colors

Paleta terrosa e quente, com um verde-pinho profundo como voz de ação.

### Primary
- **Pinho** (#114B3E): ação no tema claro (Entrar, filtro ativo, "Ver detalhes", selo "Novos materiais toda semana", Comprar com Pix), sempre com texto Papiro (9,29:1); também link (8,15:1 sobre Pedra) e anel de foco.
- **Terracota** (#F08A4B): ação no tema escuro, **sempre com texto Carvão** (7,33:1); link no escuro (7,65:1). No claro, só decorativa.

### Neutral
- **Pedra** (#E9E8E2): fundo da página no claro.
- **Papiro** (#FAF6F0): cartões, busca e superfícies no claro; texto no escuro.
- **Carvão** (#121615): texto no claro (14,86:1 sobre Pedra); superfícies no escuro.
- **Noite verde** (#0D110F): fundo da página no escuro.
- **Pedra funda** (#DEDCD4) / **Musgo escuro** (#1A201D): fundos discretos (muted, secundário).
- **Texto secundário** (#4F5A55 / #A9B0AC): legendas e metadados (5,85:1 e 8,60:1).
- **Traço** (#D3D0C6 / #26302C): divisórias e bordas de cartão (decorativas).
- **Borda de campo** (#737973 / #6B7570): contorno de campos de formulário, ≥ 3:1.
- **Erro** (#B42318 / #FF8A7A): mensagens de erro (≥ 4,79:1).

### Capas (fundo de reserva)
Cada Produto usa a capa enviada pelo Administrador. Estas cores valem só como fundo enquanto a imagem carrega ou na falta dela: Finanças #114B3E / #133B4D, Direito #8C3B20, Segurança #182026, Saúde #1F5C4D, Educação #16264C.

### Named Rules
**A regra da terracota.** No tema claro, Terracota nunca é texto nem fundo de texto (2,03:1 sobre Pedra reprova). Só marcador, traço e o ponto do logo.

**A regra do sem-roxo.** Nenhum roxo nem verde-limão em lugar nenhum, inclusive gráficos e estados.

## Typography

**Display Font:** Fraunces (Variable, com Georgia)
**Body Font:** Plus Jakarta Sans (Variable, com system-ui)

**Character:** uma serifada editorial com calor de livro, contra uma sans geométrica e limpa para tudo que se clica ou se lê de relance.

Fontes servidas pelo próprio site via @fontsource (eixo `wght` e o itálico da Fraunces); nunca Google Fonts.

### Hierarchy
- **Display / Headline** (Fraunces 600, letter-spacing -0.025em): títulos `h1`–`h3`. No Hero, "Conhecimento pronto *para usar.*" com "para usar." em itálico, na cor de ação e com `white-space: nowrap`.
- **Title** (Plus Jakarta Sans 600): títulos de card.
- **Body** (Plus Jakarta Sans 500): texto corrido, até ~70 caracteres por linha.
- **Label** (Plus Jakarta Sans 600/700): navegação, filtros, botões.
- **Preço** (Plus Jakarta Sans 700, `font-variant-numeric: tabular-nums`).

### Named Rules
**A regra dos papéis.** Fraunces nunca em botão, rótulo, campo ou preço.

## Layout

Conteúdo centralizado numa coluna de até 72rem (`max-w-6xl`) com margem lateral de 16 px no celular e 24 px a partir de 640 px. Cabeçalho de 64 px. Espaçamento na escala de 4 px do Tailwind. Sem rolagem horizontal em 390 px.

## Elevation & Depth

Profundidade por camada tonal, não por sombra: página (Pedra / Noite verde) → superfície (Papiro / Carvão) → traço. Sombras ficam fora do sistema até uma tela pedir.

## Shapes

Cantos arredondados moderados (raio base 10 px; botões e campos 8 px; cartões 14 px). O símbolo da marca é um quadrado arredondado com um livro aberto e um ponto.

## Components

### Buttons
- **Primary:** fundo da cor de ação, texto Papiro (claro) ou Carvão (escuro); um botão primário por bloco.
- **Ghost / Outline:** para ações secundárias e o botão de tema (só ícone, com nome acessível).
- **Focus:** anel na cor de ação, sempre visível pelo teclado.

### Cards / Containers
- **Background:** Papiro / Carvão sobre a página Pedra / Noite verde.
- **Border:** traço de 1 px.
- **Corner Style:** 14 px.

### Inputs / Fields
- **Style:** fundo da superfície, contorno Borda de campo (≥ 3:1), raio 8 px.
- **Focus:** anel na cor de ação. **Error:** texto e contorno na cor de erro.

### Navigation
- Cabeçalho na superfície com traço inferior: marca à esquerda, botão de tema à direita.
- Rodapé: marca pequena, a frase "Conhecimento pronto para usar.", links legais e de suporte, "© 2026 SapiensIA".

### Marca
Símbolo (quadrado na cor de ação, livro aberto no tom do texto do botão, ponto Terracota no claro e Papiro no escuro) + "SapiensIA" em Fraunces 600, com "IA" em itálico na cor de ação.

### Tema
Segue `prefers-color-scheme` até o visitante escolher; a escolha fica em `localStorage["sapiensia-theme"]` e é aplicada antes da primeira pintura por `/theme-init.js`.

## Do's and Don'ts

### Do:
- **Do** usar Pinho (#114B3E) com Papiro e Terracota (#F08A4B) com Carvão (#121615) nos botões de ação.
- **Do** manter a capa como o maior elemento do card e da página do Produto.
- **Do** respeitar "reduzir movimento"; animação só onde explica uma mudança.
- **Do** conferir contraste novo com `theme-tokens.test.ts` e o axe do e2e nos dois temas.

### Don't:
- **Don't** usar Terracota como texto ou fundo de texto no tema claro.
- **Don't** usar roxo nem verde-limão.
- **Don't** carregar fontes do Google Fonts.
- **Don't** usar Fraunces em botões, rótulos, campos ou preços.

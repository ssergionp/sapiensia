import { ORDER_TEXTS } from "../orders/messages";

// Texts shared by the customer and the administrator screens, so identical wording lives in one place.
// Single source for the purchase notice: the wording lives in ORDER_TEXTS.
export const LOGIN_PURCHASE_NOTICE = ORDER_TEXTS.loginNotice;
export const LOGIN_BLOCKED =
  "Muitas tentativas de entrada. Aguarde alguns minutos e tente de novo.";
export const LOGIN_UNEXPECTED_ERROR =
  "Não foi possível entrar agora. Tente novamente em instantes.";
export const LOGOUT_ERROR = "Não foi possível sair agora. Tente novamente.";

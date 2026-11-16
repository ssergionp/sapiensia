// A 429 from the API (RATE_LIMITED): the screens show this text instead of their generic error.
export const TOO_MANY_REQUESTS =
  "Muitas tentativas. Aguarde alguns minutos e tente de novo.";

export class TooManyRequestsError extends Error {
  constructor() {
    super("Too many requests");
    this.name = "TooManyRequestsError";
  }
}

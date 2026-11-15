const parts = new Intl.DateTimeFormat("pt-BR", {
  timeZone: "America/Sao_Paulo",
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

// The date ("09/11/2026") and time ("14:32") of an instant in Brasilia time, whatever the zone of the browser.
export function saoPauloDateAndTime(iso: string): {
  date: string;
  time: string;
} {
  const found: Record<string, string> = {};
  for (const part of parts.formatToParts(new Date(iso))) {
    found[part.type] = part.value;
  }
  return {
    date: `${found.day}/${found.month}/${found.year}`,
    time: `${found.hour}:${found.minute}`,
  };
}

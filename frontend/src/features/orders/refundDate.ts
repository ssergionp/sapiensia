// "2026-11-26" -> "26/11/2026", from the string itself: a calendar day has no time zone.
export function dayFromIsoDate(isoDate: string): string {
  const [year, month, day] = isoDate.split("-");
  return `${day}/${month}/${year}`;
}

// Starts the browser download. Kept in its own file so tests can replace it.
export function startDownload(url: string): void {
  window.location.assign(url);
}

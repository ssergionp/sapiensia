import { useEffect } from "react";

export const DEFAULT_TITLE = "SapiensIA";

// Sets the tab title while the calling page is mounted; on unmount (or title change) it falls back to the
// default so a page without its own title never shows the previous page's one.
export function useDocumentTitle(title: string): void {
  useEffect(() => {
    document.title = title;
    return () => {
      document.title = DEFAULT_TITLE;
    };
  }, [title]);
}

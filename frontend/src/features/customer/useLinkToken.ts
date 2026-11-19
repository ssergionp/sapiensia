import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router";

/**
 * Returns the token a link carries in the URL fragment (so it is never sent to servers or
 * logs) and takes it out of the address bar and the history entry once read. The token
 * lives only in React state; reloading the page therefore finds no token. A new fragment
 * (another link opened in the same tab) replaces the token.
 */
export function useLinkToken(): string {
  const { pathname, search, hash } = useLocation();
  const navigate = useNavigate();
  const fragment = hash.replace(/^#/, "");
  const [token, setToken] = useState(fragment);
  // A fragment that is not in state yet (a new link in the same tab) shows up on its own
  // render, so the form never sees the previous token with a new fragment.
  const current = fragment || token;

  useEffect(() => {
    if (!fragment) return;
    setToken(fragment);
    navigate({ pathname, search, hash: "" }, { replace: true });
  }, [fragment, pathname, search, navigate]);

  return current;
}

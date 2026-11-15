import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router";
import { Button } from "@/components/ui/button";
import { rememberReturnTo } from "@/features/orders/returnTo";
import {
  DownloadLimitError,
  DownloadUnavailableError,
  NotLoggedInError,
} from "./api";
import { useDownloadLink } from "./hooks";
import { LIBRARY_TEXTS as T } from "./messages";

type Outcome = "none" | "started" | "failed" | "unavailable" | "limit";

// One per Library item, with its own mutation, so the state of one item never shows in another.
// The link is used once inside the mutation function (see useDownloadLink) and discarded: it is not kept in state, cache or the DOM.
export default function DownloadButton({
  productId,
  title,
}: {
  productId: string;
  title: string;
}) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const download = useDownloadLink();
  const [outcome, setOutcome] = useState<Outcome>("none");

  function onClick() {
    if (download.isPending) return;
    setOutcome("none");
    download.mutate(productId, {
      onSuccess: () => setOutcome("started"),
      onError: (error) => {
        if (error instanceof NotLoggedInError) {
          rememberReturnTo("/biblioteca");
          navigate("/entrar");
        } else if (error instanceof DownloadUnavailableError) {
          setOutcome("unavailable");
          void queryClient.invalidateQueries({ queryKey: ["library"] });
        } else if (error instanceof DownloadLimitError) {
          setOutcome("limit");
        } else {
          setOutcome("failed");
        }
      },
    });
  }

  return (
    <>
      <Button
        className="min-h-11 w-full aria-disabled:cursor-default aria-disabled:opacity-50 sm:w-auto"
        aria-label={T.downloadLabel(title)}
        // Not the native `disabled`: Chrome would drop the keyboard focus to the page body. onClick ignores clicks while pending.
        aria-disabled={download.isPending}
        onClick={onClick}
      >
        {download.isPending ? T.downloadPreparing : T.download}
      </Button>
      <div aria-live="polite" className="mt-2 text-sm">
        {outcome === "started" && <p role="status">{T.downloadStarted}</p>}
        {outcome === "failed" && (
          <p role="alert" className="text-destructive">
            {T.downloadError}
          </p>
        )}
        {outcome === "limit" && (
          <p role="alert" className="text-destructive">
            {T.downloadLimit}
          </p>
        )}
        {outcome === "unavailable" && (
          <p role="alert" className="text-destructive">
            {T.downloadUnavailable}
          </p>
        )}
      </div>
    </>
  );
}

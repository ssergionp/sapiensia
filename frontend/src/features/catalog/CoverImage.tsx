import { useState } from "react";
import { cn } from "@/lib/utils";

type Props = { src: string | null; alt: string; className?: string };

// 2:3 frame, the whole image is shown (nothing is cropped). Without a source, or when the image fails to
// load, a neutral frame of the same ratio with no text takes its place.
export default function CoverImage({ src, alt, className }: Props) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  if (src === null || failedSrc === src) {
    return (
      <div
        aria-hidden="true"
        className={cn("aspect-[2/3] w-full rounded-md bg-muted", className)}
      />
    );
  }
  return (
    <img
      src={src}
      alt={alt}
      loading="lazy"
      decoding="async"
      onError={() => setFailedSrc(src)}
      className={cn(
        "aspect-[2/3] w-full rounded-md bg-muted object-contain",
        className,
      )}
    />
  );
}

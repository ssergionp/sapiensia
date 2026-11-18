import { useState } from "react";
import type { ProductCategory } from "@/features/products/schemas";
import { cn } from "@/lib/utils";

type Props = {
  src: string | null;
  alt: string;
  className?: string;
  // With a category, the fallback takes its color and shows the title (DESIGN.md, "Capas")
  category?: ProductCategory;
  title?: string;
};

// Reserve backgrounds while the cover loads or when there is none. All dark, so Papiro text passes AA.
const COVER_COLORS: Record<ProductCategory, string> = {
  FINANCE: "#114B3E",
  LAW: "#8C3B20",
  SECURITY: "#182026",
  HEALTH: "#1F5C4D",
  EDUCATION: "#16264C",
  TECHNOLOGY: "#133B4D",
  BUSINESS: "#114B3E",
  OTHER: "#182026",
};
const NEUTRAL_COVER = "#182026";

// 2:3 frame, the whole image is shown (nothing is cropped). Without a source, or when the image fails to
// load, a frame of the same ratio takes its place: neutral, or the category color with the title drawn by
// CSS from data-title (aria-hidden, so the page's own title is never read twice).
export default function CoverImage({
  src,
  alt,
  className,
  category,
  title,
}: Props) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  // A title without a category (the Library has none) gets the neutral dark reserve color
  const background = category
    ? COVER_COLORS[category]
    : title
      ? NEUTRAL_COVER
      : undefined;
  if (src === null || failedSrc === src) {
    return (
      <div
        aria-hidden="true"
        data-category={category}
        data-title={title}
        style={background ? { backgroundColor: background } : undefined}
        className={cn(
          "aspect-[2/3] w-full rounded-md bg-muted",
          title &&
            "flex items-end p-[8%] font-heading text-lg leading-tight font-semibold text-balance text-[#FAF6F0] [overflow-wrap:anywhere] before:line-clamp-5 before:content-[attr(data-title)]",
          className,
        )}
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
      style={background ? { backgroundColor: background } : undefined}
      className={cn(
        "aspect-[2/3] w-full rounded-md bg-muted object-contain",
        className,
      )}
    />
  );
}

// Builds a minimal valid 1-page PDF in memory (no PDF file in git, no dependency).
// The marker goes into the page content stream, so two PDFs with different markers have different bytes.
export function buildPdf(marker = "SapiensIA", pages = 1): Buffer {
  const text = marker.replace(/[()\\]/g, "");
  const stream = `BT /F1 18 Tf 72 720 Td (${text}) Tj ET`;
  const PAGE =
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>";
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    `<< /Type /Pages /Kids [${Array.from({ length: pages }, (_, i) => (i === 0 ? "3" : String(5 + i))).join(" 0 R ")} 0 R] /Count ${pages} >>`,
    PAGE,
    `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`,
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    // Extra pages are objects 6, 7, ... (object 5 is the font).
    ...Array.from({ length: pages - 1 }, () => PAGE),
  ];
  let body = "%PDF-1.4\n";
  const offsets: number[] = [];
  objects.forEach((obj, i) => {
    offsets.push(body.length);
    body += `${i + 1} 0 obj\n${obj}\nendobj\n`;
  });
  const xrefAt = body.length;
  body += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const offset of offsets) {
    body += `${String(offset).padStart(10, "0")} 00000 n \n`;
  }
  body += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xrefAt}\n%%EOF\n`;
  // ASCII only, so string length equals byte length and the offsets are exact.
  return Buffer.from(body, "latin1");
}

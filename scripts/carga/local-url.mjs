// The local-only guard of the load test. Pure (no k6 imports), so k6 and Node tests load the same file.
const LOCAL_HOST = /^https?:\/\/(127\.0\.0\.1|localhost|host\.docker\.internal)(:\d{1,5})?$/;
const DEFAULT_URL = "http://host.docker.internal:8081";

/** The API address without trailing slash; throws unless it is local or the override is exactly "sim". */
export function checkBaseUrl(raw, permitRemote) {
  const url = (raw === undefined ? DEFAULT_URL : raw).replace(/\/+$/, "");
  if (!LOCAL_HOST.test(url) && permitRemote !== "sim") {
    throw new Error(
      `BASE_URL ${url} não é local. Este teste só roda contra o sistema local; ` +
        "para outro ambiente é preciso a autorização do responsável e CARGA_PERMITIR_REMOTO=sim.",
    );
  }
  return url;
}

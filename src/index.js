const SERVICE_NAME = "Nishgamerrhub metadata API";
const WORKER_VERSION = "1.0.0";

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
};

function jsonResponse(request, payload, status = 200, extraHeaders = {}) {
  const headers = new Headers(JSON_HEADERS);
  for (const [name, value] of Object.entries(extraHeaders)) {
    headers.set(name, value);
  }

  return new Response(
    request.method === "HEAD" ? null : JSON.stringify(payload, null, 2),
    { status, headers },
  );
}

const worker = {
  fetch(request) {
    const url = new URL(request.url);

    if (request.method !== "GET" && request.method !== "HEAD") {
      return jsonResponse(
        request,
        { error: "Method not allowed", allowedMethods: ["GET", "HEAD"] },
        405,
        { allow: "GET, HEAD" },
      );
    }

    if (url.pathname === "/") {
      return jsonResponse(request, {
        service: SERVICE_NAME,
        purpose: "Read-only status and version metadata",
        endpoints: ["/health", "/version"],
        executableLua: false,
      });
    }

    if (url.pathname === "/health") {
      return jsonResponse(request, { status: "ok" });
    }

    if (url.pathname === "/version") {
      return jsonResponse(request, {
        service: SERVICE_NAME,
        version: WORKER_VERSION,
        contentType: "metadata-only",
      });
    }

    return jsonResponse(request, { error: "Not found" }, 404);
  },
};

export default worker;

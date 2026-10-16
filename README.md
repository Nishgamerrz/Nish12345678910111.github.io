# Ai — Gemini Chat

A single-file, zero-dependency web chat client for Google's Gemini API.

👉 **`index.html`** — open it in any browser and chat. No build step, no server, no
dependencies, no backend. It talks straight to Google's REST API from the page.

## Features

- **Streaming text chat** over `:streamGenerateContent?alt=sse` — replies appear token
  by token, with a Stop button mid-generation.
- **Image generation** — flip the composer to *Image* mode and describe a picture.
  Tries `gemini-2.5-flash-image` (two response-modalitiy shapes) and falls back to
  Imagen `:predict`, downloading the first result that comes back.
- **Model picker** — 2.5 Flash / Flash-Lite / Pro / 2.0 Flash.
- **Markdown rendering** with a hand-rolled, escape-first renderer (headings, bold,
  italic, inline code, fenced code blocks, lists, quotes, links, tables-ready).
  User and model text is HTML-escaped *before* any formatting is applied, so a
  response containing `<script>` cannot execute.
- **Conversation memory** — the last 20 turns are sent for context.
- **Local persistence** — your API key and chat history live in `localStorage` only.
- **Friendly errors** — invalid key, quota exhausted, safety-filtered, empty response
  and network failures each get a specific, actionable message with the raw detail.

## Run it

Just open the file:

```bash
xdg-open index.html      # macOS: open index.html
```

Or serve it locally (any static server works):

```bash
python3 -m http.server 8000
# then visit http://localhost:8000
```

Paste your API key when the dialog appears — it is stored in your browser and sent
only to `generativelanguage.googleapis.com`.

## Deploy it on GitHub Pages

1. Repo **Settings → General → Danger Zone → Change visibility → Public**
2. Repo **Settings → Pages → Source: Deploy from a branch → `main` → `/ (root)` → Save**
3. Wait ~1 minute, then open `https://<your-username>.github.io/Ai/`

Because the page is static and keyless on the server side, Pages can host it as-is.
Anyone who visits will be asked for **their own** API key — your key is never part of
the deployed site and never leaves your browser.

## Also in this repo

- **`gemini_demo.py`** — the same two calls (text + image) from the command line,
  standard library only. `export GEMINI_API_KEY=…` then `python3 gemini_demo.py --help`
- **`GEMINI_SETUP.md`** — setup notes, billing caveats, and why this sandbox cannot
  reach Google's API (outbound egress is restricted to a small host allowlist).

## ⚠️ Never commit a real API key

The page asks for the key at runtime and keeps it in `localStorage`; there is no
config file to accidentally commit. If you ever paste a key into a chat, an issue, or
a screenshot, revoke it at <https://aistudio.google.com/apikey> and make a new one.

## Notes on Gemini's free tier

Text models work on the free tier. Image generation generally requires **billing
enabled** on the key's Google Cloud project — without it you will see a `429`
(`RESOURCE_EXHAUSTED`) or a 403 mentioning billing. The app surfaces that exact
message rather than failing silently.

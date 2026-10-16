# Using your Gemini API key

## ⚠️ First: rotate the key you pasted in chat

The key `AQ.Ab8…Ns5Tlw` was pasted into a chat message, so it must be treated as
compromised. Anyone who can read that conversation can spend your quota and, if the
key is attached to a Google Cloud project, potentially reach other enabled APIs.

1. Go to <https://aistudio.google.com/apikey>
2. Delete / revoke the key that starts with `AQ.Ab8`
3. Create a new one and keep it **out of chat, out of Git, out of screenshots**

For anything beyond a throwaway test, put the key in a `.env` file that is listed in
`.gitignore`, or in your OS keychain / a secrets manager.

## Second: this sandbox cannot reach Google

The Arena sandbox this repo runs in only allows outbound traffic to GitHub, npm and
PyPI. Every `*.googleapis.com` host is blocked at the firewall:

```
$ curl -sS https://generativelanguage.googleapis.com/v1beta/models
curl: (35) OpenSSL SSL_connect: SSL_ERROR_SYSCALL   # TLS torn down by the egress proxy

$ curl -s -o /dev/null -w '%{http_code}\n' https://pypi.org/simple/
200                                                 # control — other hosts work fine
```

So the key was never transmitted anywhere: no request was made, and nothing was
logged to Google. The API call has to come from a machine with normal internet
access — your laptop, a VPS, Colab, or GitHub Actions (store the key as a repository
secret there, never in code).

## Run it yourself in one command

`gemini_demo.py` is stdlib-only — no pip install, no SDK. It sends one text prompt
and one image prompt to Gemini and saves the returned PNG.

```bash
export GEMINI_API_KEY="your-new-key-here"      # never commit this line

python3 gemini_demo.py                                        # built-in demo prompts
python3 gemini_demo.py --ask "Why is the sky blue?"           # text only
python3 gemini_demo.py --image "a snow leopard at dusk"       # image only
python3 gemini_demo.py --ask "..." --image "..."              # both
python3 gemini_demo.py --image "..." --out fox.png            # choose the output file
```

What it does under the hood:

| Step | Endpoint | Model (override with env var) |
|---|---|---|
| Text | `POST /v1beta/models/{model}:generateContent` | `GEMINI_TEXT_MODEL` (default `gemini-2.5-flash`) |
| Image | `POST /v1beta/models/{model}:generateContent` | `GEMINI_IMAGE_MODEL` (default `gemini-2.5-flash-image`) |
| Image fallback | `POST /v1beta/models/{model}:predict` | `GEMINI_IMAGE_MODEL_FALLBACK` (default `imagen-4.0-generate-001`) |

Authentication is the `x-goog-api-key: <key>` header. Because Google has moved image
generation between models and response shapes more than once, the script tries each
known variant in order and prints the exact Google error if all of them fail — so if
a model name has been retired by the time you run it, you will see precisely which
one and why, instead of a stack trace.

## Free-tier reality check

Text on `gemini-2.5-flash` has a usable free tier. Image generation is generally
**billed** — on a free-tier-only key you will likely get `HTTP 429`
(`RESOURCE_EXHAUSTED`) or a 403 telling you billing is required. If you only want a
picture without a billing account, any of these work: Google AI Studio's web UI
(<https://aistudio.google.com>) with your Google account, or the image tool in this
Arena session (`fox_demo.png` in this repo was produced that way).

#!/usr/bin/env python3
"""
gemini_demo.py — ask Gemini a question and have it generate an image.

Standard library only: no `pip install` required. Python 3.8+.

Usage
-----
    export GEMINI_API_KEY="your-key-here"

    python3 gemini_demo.py                              # built-in demo prompt, text + image
    python3 gemini_demo.py --ask "Explain black holes in three sentences"
    python3 gemini_demo.py --image "a red fox in tall grass at sunrise"
    python3 gemini_demo.py --ask "..." --image "..."    # do both

Output
------
The model's text answer is printed to the terminal; the generated image is
saved as a PNG next to this script (see --out).

Notes
-----
* The API key is read from the GEMINI_API_KEY environment variable only.
  It is never hardcoded or written to disk.
* Image generation is tried against several model/endpoint shapes because
  Google has moved this around; the first one that works wins, and the exact
  Google error is shown if all of them fail.
"""

import argparse
import base64
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.request

API_ROOT = "https://generativelanguage.googleapis.com/v1beta"

TEXT_MODEL = os.environ.get("GEMINI_TEXT_MODEL", "gemini-2.5-flash")
IMAGE_MODEL = os.environ.get("GEMINI_IMAGE_MODEL", "gemini-2.5-flash-image")
FALLBACK_IMAGE_MODEL = os.environ.get("GEMINI_IMAGE_MODEL_FALLBACK", "imagen-4.0-generate-001")

DEFAULT_ASK = "In three sentences, tell me something genuinely interesting about the ocean."
DEFAULT_IMAGE = (
    "A photorealistic photograph of a red fox standing in tall golden grass at "
    "sunrise, shallow depth of field, natural warm light, sharp fur detail."
)


def call(path, payload, key, timeout=180):
    """POST a JSON payload to the Gemini REST API and return the parsed JSON."""
    url = f"{API_ROOT}/{path}"
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "x-goog-api-key": key,
            # Some proxies prefer the query-string form; the header is the modern one.
            "User-Agent": "gemini_demo.py/1.0",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code} from {path}\n{detail}") from None
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Network error reaching {url}: {exc.reason}") from None


# --------------------------------------------------------------------------- #
# Text
# --------------------------------------------------------------------------- #
def ask(prompt, key):
    """Return the model's text answer as a string."""
    out = call(
        f"models/{TEXT_MODEL}:generateContent",
        {"contents": [{"parts": [{"text": prompt}]}]},
        key,
    )

    chunks = []
    for candidate in out.get("candidates", []):
        for part in candidate.get("content", {}).get("parts", []):
            if isinstance(part.get("text"), str):
                chunks.append(part["text"])

    if not chunks:
        # Surface safety blocks / other non-text finishes instead of printing nothing.
        reason = next(
            (c.get("finishReason") for c in out.get("candidates", []) if c.get("finishReason")),
            None,
        )
        raise RuntimeError(
            "The model returned no text"
            + (f" (finishReason: {reason})" if reason else "")
            + f".\nRaw response:\n{json.dumps(out, indent=2)[:2000]}"
        )

    return "\n".join(chunks).strip()


# --------------------------------------------------------------------------- #
# Image
# --------------------------------------------------------------------------- #
def _inline_images(out):
    """Pull base64 images out of a generateContent response."""
    found = []
    for candidate in out.get("candidates", []):
        for part in candidate.get("content", {}).get("parts", []):
            inline = part.get("inlineData") or part.get("inline_data")
            if inline and inline.get("data"):
                mime = inline.get("mimeType") or inline.get("mime_type") or "image/png"
                found.append((mime, inline["data"]))
    return found


def _predict_images(out):
    """Pull base64 images out of an :predict (Imagen) response."""
    found = []
    for pred in out.get("predictions", []):
        data = pred.get("bytesBase64Encoded") or pred.get("bytes_base64_encoded")
        if data:
            found.append((pred.get("mimeType") or "image/png", data))
    return found


def generate_image(prompt, key):
    """Try each known image endpoint in turn. Returns (mime, base64, label)."""
    attempts = [
        (
            f"{IMAGE_MODEL} (responseModalities: IMAGE)",
            f"models/{IMAGE_MODEL}:generateContent",
            {
                "contents": [{"parts": [{"text": prompt}]}],
                "generationConfig": {"responseModalities": ["IMAGE"]},
            },
            _inline_images,
        ),
        (
            f"{IMAGE_MODEL} (responseModalities: TEXT, IMAGE)",
            f"models/{IMAGE_MODEL}:generateContent",
            {
                "contents": [{"parts": [{"text": prompt}]}],
                "generationConfig": {"responseModalities": ["TEXT", "IMAGE"]},
            },
            _inline_images,
        ),
        (
            f"{FALLBACK_IMAGE_MODEL} (Imagen :predict)",
            f"models/{FALLBACK_IMAGE_MODEL}:predict",
            {"instances": [{"prompt": prompt}], "parameters": {"sampleCount": 1}},
            _predict_images,
        ),
    ]

    errors = []
    for label, path, payload, extractor in attempts:
        print(f"  ... trying {label}", flush=True)
        try:
            out = call(path, payload, key)
        except RuntimeError as exc:
            first_line = str(exc).splitlines()[0]
            errors.append(f"{label}: {first_line}")
            continue

        images = extractor(out)
        if images:
            return images[0][0], images[0][1], label
        errors.append(f"{label}: response contained no image data")

    raise RuntimeError("All image endpoints failed:\n  - " + "\n  - ".join(errors))


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def extension_for(mime, fallback=".png"):
    ext = mimetypes.guess_extension(mime.split(";")[0].strip()) if mime else None
    return ext if ext in (".png", ".jpg", ".jpeg", ".webp") else fallback


def main():
    parser = argparse.ArgumentParser(description="Ask Gemini a question and generate an image.")
    parser.add_argument("--ask", nargs="?", const=DEFAULT_ASK, default=None,
                        help="text prompt to send to Gemini")
    parser.add_argument("--image", nargs="?", const=DEFAULT_IMAGE, default=None,
                        help="image prompt to send to Gemini's image model")
    parser.add_argument("--out", default=None, help="output path for the image (default: gemini_image.png)")
    args = parser.parse_args()

    key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not key:
        print("ERROR: GEMINI_API_KEY is not set.\n"
              "   export GEMINI_API_KEY=\"your-key-here\"\n"
              "Get / rotate a key at https://aistudio.google.com/apikey", file=sys.stderr)
        return 2

    # With no flags, run both demos.
    do_ask = args.ask is not None or (args.ask is None and args.image is None)
    do_image = args.image is not None or (args.ask is None and args.image is None)
    ask_prompt = args.ask if args.ask is not None else DEFAULT_ASK
    image_prompt = args.image if args.image is not None else DEFAULT_IMAGE

    if do_ask:
        print(f"\n=== TEXT RESPONSE ({TEXT_MODEL}) ===")
        print(f"prompt: {ask_prompt}\n")
        try:
            print(ask(ask_prompt, key))
        except RuntimeError as exc:
            print(f"FAILED: {exc}", file=sys.stderr)

    if do_image:
        print(f"\n=== IMAGE GENERATION ===")
        print(f"prompt: {image_prompt}\n")
        try:
            mime, b64, label = generate_image(image_prompt, key)
        except RuntimeError as exc:
            print(f"FAILED: {exc}", file=sys.stderr)
            return 1

        out_path = args.out or ("gemini_image" + extension_for(mime))
        with open(out_path, "wb") as fh:
            fh.write(base64.b64decode(b64))
        print(f"image saved -> {out_path}  ({mime}, via {label})")

    return 0


if __name__ == "__main__":
    sys.exit(main())

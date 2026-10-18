# Nishgamerr Hub Loader

A lightweight, static landing page for a Roblox Studio creator hub. The page contains a safe local ModuleScript pattern and setup notes; it does not fetch remote Luau, use `loadstring`, or target exploit executors.

## Preview locally

Open `index.html` in a browser, or run a static server from this directory:

```bash
python3 -m http.server 8000
```

Then open `http://localhost:8000`.

## Deploy

This is a zero-dependency static site and can be deployed directly to Vercel. The deployed page is public; do not place secrets or private game logic in client-delivered code. For game logic, use server Scripts/ModuleScripts in Roblox Studio and validate client requests on the server.

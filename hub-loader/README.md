# Nishgamerr Hub Loader

A static landing page for a Roblox Studio creator hub. It includes a safe local ModuleScript pattern and setup notes; it does not fetch remote Luau, use `loadstring`, or target exploit executors.

## Preview locally

Open `index.html` in a browser, or run a static server from this directory:

```bash
python3 -m http.server 8000
```

Then open `http://localhost:8000`.

## Build and deploy

There are no runtime dependencies. The Vercel build runs `npm run build`; `build.js` copies the static page into `dist/`.

The deployed page is public. Do not put secrets or private game logic in client-delivered code. For game logic, use server Scripts/ModuleScripts in Roblox Studio and validate client requests on the server.

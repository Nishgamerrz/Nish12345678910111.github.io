# Nishgamerrhub metadata Worker

A small Cloudflare Worker that exposes public health and version metadata:

- `GET /` — service information and available routes
- `GET /health` — health status
- `GET /version` — Worker version metadata

## Important safety boundary

This project is **not** a Roblox executor loader. It intentionally does not return Lua source, proxy the `myscripts` repository, or execute remote code. The requested `loadstring(game:HttpGet(...))()` pattern runs unverified code inside a Roblox executor; this Worker is limited to JSON metadata instead.

For code in an experience you own, use Roblox Studio and a `ModuleScript`/`require` workflow. A Worker can safely provide JSON configuration or version information, but it should not be used to distribute executable game code to an executor.

## Test locally

Requires Node.js 20 or newer.

```sh
npm install
npm test
```

## Deploy to Cloudflare Workers

### With Wrangler

```sh
npx wrangler login
npm run deploy
```

Cloudflare will assign a `*.workers.dev` URL. The root route returns JSON; it is not a Lua payload endpoint.

### From GitHub

Connect this repository to Cloudflare Workers Builds, set the repository root to `/`, and use `npm install` as the install step and `npm run deploy` as the deploy command. You can run `npm test` as a build/check step. Select the branch you want Cloudflare to deploy.

The Worker configuration is in [`wrangler.toml`](./wrangler.toml), and the tests run with Node's built-in test runner.

# Web UI Workspace

This directory is the frontend workspace for local development and bundling only.

Production deployment is still a single Java jar:

```bash
java -jar target/discord-music-bot-1.4.jar
```

## Scope

- Node.js/Vite is used only for Web UI asset development and build.
- Java backend still handles:
  - `/api/**`
  - session and auth
  - Discord OAuth callback flow
  - static file hosting from classpath `/web/**`

## Directory Mapping

- Frontend source: `web/src`
- Build output (jar resources): `src/main/resources/web`

Notes:

- Vite only emits bundled files (`app.js`, `app.css`) to `src/main/resources/web`.
- Java runtime templates stay in `src/main/resources/web/templates` and are not managed by Vite.

## Commands

Install dependencies:

```bash
cd web
npm install
```

Build once:

```bash
npm run build
```

Watch mode (recommended with local Java server running):

```bash
npm run dev
```

Optional standalone Vite dev server:

```bash
npm run dev:server
```

## Recommended Local Workflow

1. Start Java backend (same as current project workflow).
2. Start `npm run dev` in `web/`.
3. Open Java Web UI URL (for example `http://localhost:60000`).
4. Vite watcher updates `src/main/resources/web/app.js` and `app.css` automatically.

This keeps backend behavior identical to production while enabling a frontend toolchain in `web/`.

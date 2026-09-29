import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Quarkus (Quinoa) serves this app on :8080 and proxies to the Vite dev server in dev mode,
// so /api calls are same-origin and need no proxy here.
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true },
});

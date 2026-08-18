export default defineNuxtConfig({
  compatibilityDate: '2026-08-09',
  ssr: false,
  srcDir: '../src',
  dir: {
    app: 'dashboard',
    pages: 'dashboard/pages',
    public: '../app/public',
  },
  devtools: { enabled: false },
  app: {
    buildAssetsDir: '/web/dashboard/_nuxt/',
    head: {
      htmlAttrs: { lang: 'zh-TW' },
      title: 'NoRule Bot｜伺服器控制面板',
      meta: [
        { name: 'description', content: 'NoRule Bot 的 Discord 伺服器管理控制台。' },
        { name: 'theme-color', content: '#171816' },
      ],
    },
  },
  css: ['~/dashboard/assets/css/dashboard.css'],
  components: [{ path: '~/dashboard/components', pathPrefix: false }],
  typescript: { strict: true, typeCheck: false },
  vite: {
    server: {
      proxy: {
        '/api': {
          target: process.env.NUXT_DASHBOARD_API_TARGET || 'http://127.0.0.1:60000',
          changeOrigin: true,
        },
        '/auth': {
          target: process.env.NUXT_DASHBOARD_API_TARGET || 'http://127.0.0.1:60000',
          changeOrigin: true,
        },
      },
    },
  },
  nitro: {
    output: { dir: '../.output-dashboard' },
    prerender: {
      crawlLinks: false,
      routes: ['/'],
    },
  },
})

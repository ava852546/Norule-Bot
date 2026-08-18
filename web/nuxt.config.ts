export default defineNuxtConfig({
  compatibilityDate: '2026-08-09',
  ssr: false,
  dir: { public: 'app/public' },
  devtools: { enabled: false },
  app: {
    buildAssetsDir: '/web/short-url/_nuxt/',
    head: {
      htmlAttrs: { lang: 'zh-TW' },
      title: 'NoRule URL｜分享更短，傳得更遠',
      meta: [
        { name: 'description', content: '快速建立簡潔好記的短網址，或分享設有到期時間與密碼保護的圖片及影片。' },
        { name: 'theme-color', content: '#f1eee5' },
      ],
    },
  },
  css: ['~/assets/css/base.css'],
  components: [{ path: '~/components', pathPrefix: false }],
  typescript: { strict: true, typeCheck: false },
  vite: {
    server: {
      proxy: {
        '/api': {
          target: process.env.NUXT_DEV_API_TARGET || 'http://127.0.0.1:60001',
          changeOrigin: true,
        },
      },
    },
  },
  nitro: {
    prerender: {
      crawlLinks: false,
      routes: ['/'],
    },
  },
})

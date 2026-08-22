import { access, copyFile, cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptsDir = path.dirname(fileURLToPath(import.meta.url))
const webRoot = path.resolve(scriptsDir, '..')
const generatedRoot = path.resolve(webRoot, '.output-dashboard/public')
const generatedAssets = path.resolve(generatedRoot, 'web/dashboard/_nuxt')
const outputRoot = process.env.NORULE_WEB_OUTPUT_DIR
  ? path.resolve(process.env.NORULE_WEB_OUTPUT_DIR)
  : path.resolve(webRoot, '../src/main/resources/web')
const outputAssets = path.resolve(outputRoot, 'dashboard/_nuxt')
const templateRoot = path.resolve(webRoot, 'src/templates')
const legacyStyleFiles = [
  'app/assets/css/tokens.css',
  'src/styles/base/tokens.css',
  'src/styles/components/layout.css',
  'src/styles/components/forms.css',
  'src/styles/components/toggle.css',
  'src/styles/components/tabs.css',
  'src/styles/components/feedback.css',
  'src/styles/components/history.css',
  'src/styles/components/modal.css',
  'src/styles/pages/hero.css',
  'src/styles/pages/dashboard.css',
  'src/styles/pages/notifications.css',
  'src/styles/pages/welcome.css',
  'src/styles/pages/contextual.css',
  'src/styles/pages/not-found.css',
  'src/styles/pages/media-share-pages.css',
]

await mkdir(outputRoot, { recursive: true })
await rm(outputAssets, { recursive: true, force: true })
await mkdir(path.dirname(outputAssets), { recursive: true })
await cp(generatedAssets, outputAssets, { recursive: true })

const generatedHtmlPath = path.resolve(generatedRoot, 'index.html')
const html = await readFile(generatedHtmlPath, 'utf8')
const assetReferences = [...html.matchAll(/(?:src|href)="(\/web\/dashboard\/_nuxt\/[^"?#]+)[^"?]*"/g)]
  .map((match) => match[1])

if (assetReferences.length === 0) {
  throw new Error('Nuxt Dashboard HTML 沒有引用 /web/dashboard/_nuxt/ 資產。')
}

await copyFile(generatedHtmlPath, path.resolve(outputRoot, 'dashboard.html'))

const legacyStyles = await Promise.all(legacyStyleFiles.map(async (fileName) => {
  const source = await readFile(path.resolve(webRoot, fileName), 'utf8')
  return source.replace(/^@import[^;]+;\s*$/gm, '').trim()
}))
await writeFile(path.resolve(outputRoot, 'app.css'), `${legacyStyles.join('\n\n')}\n`, 'utf8')
await rm(path.resolve(outputRoot, 'app.js'), { force: true })
await rm(path.resolve(outputRoot, 'chunks'), { recursive: true, force: true })

for (const fileName of ['share-expired.html']) {
  await copyFile(path.resolve(templateRoot, fileName), path.resolve(outputRoot, fileName))
}

for (const reference of assetReferences) {
  await access(path.resolve(outputRoot, reference.replace('/web/', '')))
}

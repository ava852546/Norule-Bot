import path from 'node:path'
import { access, copyFile, cp, mkdir, readFile, rm } from 'node:fs/promises'

const webRoot = path.resolve(import.meta.dirname, '..')
const generatedRoot = path.resolve(webRoot, '.output/public')
const generatedAssets = path.resolve(generatedRoot, 'web/short-url/_nuxt')
const outputRoot = process.env.NORULE_WEB_OUTPUT_DIR
  ? path.resolve(process.env.NORULE_WEB_OUTPUT_DIR)
  : path.resolve(webRoot, '../src/main/resources/web')
const outputAssets = path.resolve(outputRoot, 'short-url/_nuxt')

const relativeOutputAssets = path.relative(outputRoot, outputAssets)
if (relativeOutputAssets.startsWith('..') || path.isAbsolute(relativeOutputAssets)) {
  throw new Error(`拒絕清理輸出目錄以外的路徑：${outputAssets}`)
}

const generatedHtmlPath = path.resolve(generatedRoot, 'index.html')
const html = await readFile(generatedHtmlPath, 'utf8')
const assetReferences = [...html.matchAll(/(?:src|href)="(\/web\/short-url\/_nuxt\/[^"?#]+)[^"?]*"/g)]
  .map((match) => match[1])

if (assetReferences.length === 0) {
  throw new Error('Nuxt HTML 沒有引用 /web/short-url/_nuxt/ 資產。')
}

for (const reference of new Set(assetReferences)) {
  await access(path.resolve(generatedRoot, `.${reference}`))
}

await mkdir(outputRoot, { recursive: true })
await rm(outputAssets, { recursive: true, force: true })
await mkdir(path.dirname(outputAssets), { recursive: true })
await cp(generatedAssets, outputAssets, { recursive: true })
await copyFile(generatedHtmlPath, path.resolve(outputRoot, 'short-url.html'))
await copyFile(path.resolve(generatedRoot, 'short-url-stats.html'), path.resolve(outputRoot, 'short-url-stats.html'))

for (const reference of new Set(assetReferences)) {
  await access(path.resolve(outputRoot, reference.replace('/web/', '')))
}

console.log(`NoRule URL static output synced to ${outputRoot}`)

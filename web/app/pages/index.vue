<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

const navLinks = [
  { label: '01 短網址', href: '#shorten' },
  { label: '02 媒體分享', href: '#media' },
  { label: '03 服務規格', href: '#principles' },
]

type ToolName = 'shorten' | 'media'
const activeTool = ref<ToolName>('shorten')

function selectTool(tool: ToolName, updateHash = true) {
  activeTool.value = tool
  if (updateHash && import.meta.client) history.replaceState(null, '', tool === 'media' ? '#media' : '#shorten')
}

function syncToolFromHash() {
  if (window.location.hash === '#media') selectTool('media', false)
  if (window.location.hash === '#shorten') selectTool('shorten', false)
}

function handleNavigation(link: { href: string }) {
  if (link.href === '#media') selectTool('media', false)
  if (link.href === '#shorten') selectTool('shorten', false)
}

onMounted(() => {
  syncToolFromHash()
  window.addEventListener('hashchange', syncToolFromHash)
})
onBeforeUnmount(() => window.removeEventListener('hashchange', syncToolFromHash))

const principles = [
  { number: '01', title: '立即可用', description: '貼上網址就能建立連結，不以註冊流程打斷分享。' },
  { number: '02', title: '留下辨識度', description: '需要時自訂短碼，讓收件者知道這條連結為何而來。' },
  { number: '03', title: '分享的不只網址', description: '圖片與影片使用同一套清楚、直接的分享流程。' },
  { number: '04', title: '控制何時結束', description: '以到期時間與密碼界定媒體內容的存取範圍。' },
]
</script>

<template>
  <div id="top" class="home-page">
    <NrNavbar brand="NoRule URL" :links="navLinks" @navigate="handleNavigation">
      <template #action><ShortUrlSessionAction /></template>
    </NrNavbar>

    <main class="utility-main">
      <NrPageContainer>
        <section class="utility-shell" aria-labelledby="hero-title">
          <aside class="utility-intro">
            <div class="utility-intro__meta"><span>NR / URL</span><b><i /> PUBLIC ACCESS</b></div>
            <h1 id="hero-title">縮短，更簡單。</h1>
            <p class="utility-intro__lead">建立容易分享的短連結，或上傳有期限與密碼保護的媒體。</p>
            <dl class="utility-summary">
              <div><dt>01</dt><dd>免登入使用</dd></div>
              <div><dt>02</dt><dd>可自訂短碼</dd></div>
              <div><dt>03</dt><dd>限時媒體分享</dd></div>
            </dl>
          </aside>

          <section class="utility-workspace" aria-label="連結建立工具">
            <header class="workspace-bar"><div><span>NO RULE URL</span><strong>連結工具</strong></div><small>SIGN-IN OPTIONAL</small></header>
            <div class="tool-switch" role="tablist" aria-label="選擇分享工具">
              <button id="shorten-tab" type="button" role="tab" :aria-selected="activeTool === 'shorten'" aria-controls="shorten" :tabindex="activeTool === 'shorten' ? 0 : -1" @click="selectTool('shorten')" @keydown.right.prevent="selectTool('media')"><span>01</span>縮短網址</button>
              <button id="media-tab" type="button" role="tab" :aria-selected="activeTool === 'media'" aria-controls="media" :tabindex="activeTool === 'media' ? 0 : -1" @click="selectTool('media')" @keydown.left.prevent="selectTool('shorten')"><span>02</span>媒體分享</button>
            </div>

            <div id="shorten" v-show="activeTool === 'shorten'" class="tool-panel" role="tabpanel" aria-labelledby="shorten-tab">
              <header class="tool-panel__heading">
                <div><p>SHORT LINK / 01</p><h2>建立短連結</h2></div>
                <span>貼上完整網址；短碼可以留白，需要辨識度時再命名。</span>
              </header>
              <ShortUrlForm />
            </div>

            <div id="media" v-show="activeTool === 'media'" class="tool-panel" role="tabpanel" aria-labelledby="media-tab">
              <header class="tool-panel__heading">
                <div><p>MEDIA DROP / 02</p><h2>建立媒體連結</h2></div>
                <span>選擇圖片或影片，設定存取期限與密碼保護。</span>
              </header>
              <MediaShareForm />
            </div>
          </section>
        </section>

        <section id="principles" class="capabilities" aria-labelledby="principles-title">
          <header class="capabilities__heading">
            <p>03 / SERVICE SCOPE</p>
            <h2 id="principles-title">清楚的分享控制</h2>
            <span>從建立連結到限制存取，每個選項都直接對應實際用途。</span>
          </header>
          <ol class="capability-list">
            <li v-for="principle in principles" :key="principle.number">
              <span>{{ principle.number }}</span>
              <h3>{{ principle.title }}</h3>
              <p>{{ principle.description }}</p>
            </li>
          </ol>
        </section>
      </NrPageContainer>
    </main>

    <NrFooter brand="NoRule URL" description="短網址與限時媒體分享，為每一次傳送保留剛好的控制。">
      <nav class="footer-links" aria-label="服務資訊">
        <a href="https://www.norule.me/bot/terms-of-service/">服務條款</a>
        <a href="https://www.norule.me/bot/privacy-policy/">隱私權政策</a>
        <a href="#top">TOP / 00</a>
      </nav>
    </NrFooter>
  </div>
</template>

<style scoped>
.home-page{position:relative}.utility-main{min-height:calc(100vh - 4.55rem)}.utility-shell{display:grid;grid-template-columns:minmax(18rem,.58fr) minmax(42rem,1.42fr);align-items:stretch;padding:clamp(2rem,4vw,4rem) 0 3.5rem}.utility-intro{display:grid;min-width:0;align-content:start;padding:1.75rem clamp(1.5rem,3vw,3rem) 1.75rem 0;border-top:1px solid var(--nr-border-strong);border-bottom:1px solid var(--nr-border)}.utility-intro__meta{display:flex;align-items:center;justify-content:space-between;gap:1rem;color:var(--nr-text-muted);font-family:var(--nr-font-mono);font-size:.6rem;letter-spacing:.08em}.utility-intro__meta b{display:flex;align-items:center;gap:.45rem;color:var(--nr-success);font-size:inherit}.utility-intro__meta i{width:.45rem;height:.45rem;background:currentColor}.utility-intro h1{margin:clamp(2.5rem,5vw,4.5rem) 0 .9rem;font-family:var(--nr-font-display);font-size:clamp(2.8rem,4.2vw,4.4rem);font-weight:680;line-height:1;letter-spacing:-.035em;word-break:keep-all}.utility-intro__lead{max-width:27rem;margin:0;color:var(--nr-text-muted);font-size:.95rem;line-height:1.7}.utility-summary{display:grid;margin:clamp(2.5rem,5vw,4rem) 0 0;padding:0;border-top:1px solid var(--nr-border)}.utility-summary div{display:grid;grid-template-columns:2.5rem 1fr;gap:.75rem;padding:.8rem 0;border-bottom:1px solid var(--nr-border)}.utility-summary dt{color:var(--nr-accent);font-family:var(--nr-font-mono);font-size:.62rem;font-weight:800}.utility-summary dd{margin:0;color:var(--nr-text-muted);font-size:.72rem}.utility-workspace{min-width:0;border:1px solid var(--nr-border-strong);background:var(--nr-surface)}.workspace-bar{display:flex;min-height:4rem;align-items:center;justify-content:space-between;gap:1rem;padding:.8rem 1.4rem;border-bottom:1px solid var(--nr-border)}.workspace-bar div{display:flex;align-items:baseline;gap:.8rem}.workspace-bar span,.workspace-bar small,.tool-panel__heading p,.capabilities__heading>p{color:var(--nr-text-muted);font-family:var(--nr-font-mono);font-size:.58rem;letter-spacing:.08em}.workspace-bar strong{font-family:var(--nr-font-display);font-size:.9rem}.tool-switch{display:grid;grid-template-columns:1fr 1fr;border-bottom:1px solid var(--nr-border-strong)}.tool-switch button{display:flex;min-height:3.75rem;align-items:center;gap:.75rem;padding:0 1.4rem;border:0;color:var(--nr-text-muted);background:var(--nr-bg);font-family:var(--nr-font-display);font-size:.84rem;font-weight:700;text-align:left;cursor:pointer}.tool-switch button+button{border-left:1px solid var(--nr-border)}.tool-switch button span{font-family:var(--nr-font-mono);font-size:.6rem}.tool-switch button[aria-selected="true"]{color:var(--nr-text);background:var(--nr-bg-elevated);box-shadow:inset 0 -3px var(--nr-accent)}.tool-panel{padding:clamp(1.75rem,3vw,2.75rem)}.tool-panel__heading{display:flex;align-items:end;justify-content:space-between;gap:2rem;margin-bottom:1.6rem}.tool-panel__heading p{margin:0 0 .45rem;color:var(--nr-accent)}.tool-panel__heading h2{margin:0;font-family:var(--nr-font-display);font-size:clamp(1.65rem,2.4vw,2.35rem);font-weight:680;line-height:1}.tool-panel__heading>span{max-width:25rem;color:var(--nr-text-muted);font-size:.76rem;line-height:1.55;text-align:right}.capabilities{display:grid;grid-template-columns:minmax(16rem,.65fr) minmax(0,1.35fr);gap:clamp(2.5rem,6vw,6rem);padding:3.5rem 0 4.5rem;border-top:1px solid var(--nr-border-strong)}.capabilities__heading>p{margin:0;color:var(--nr-accent)}.capabilities__heading h2{margin:.75rem 0 .65rem;font-family:var(--nr-font-display);font-size:clamp(1.8rem,3vw,2.75rem);font-weight:670;line-height:1}.capabilities__heading>span{display:block;max-width:28rem;color:var(--nr-text-muted);font-size:.78rem;line-height:1.6}.capability-list{margin:0;padding:0;border-top:1px solid var(--nr-border-strong);list-style:none}.capability-list li{display:grid;grid-template-columns:2.5rem minmax(9rem,.6fr) minmax(14rem,1fr);gap:1rem;align-items:baseline;padding:1.15rem 0;border-bottom:1px solid var(--nr-border)}.capability-list>li>span{color:var(--nr-accent);font-family:var(--nr-font-mono);font-size:.62rem}.capability-list h3{margin:0;font-family:var(--nr-font-display);font-size:1rem}.capability-list p{margin:0;color:var(--nr-text-muted);font-size:.76rem;line-height:1.55}@media(max-width:1100px){.utility-shell{grid-template-columns:1fr;padding-top:2rem}.utility-intro{padding:1.75rem 0 2rem}.utility-intro h1{margin:2rem 0 .75rem}.utility-summary{grid-template-columns:repeat(3,minmax(0,1fr));margin-top:2rem}.utility-summary div{grid-template-columns:1fr;gap:.3rem;padding:.75rem}.utility-summary div+div{border-left:1px solid var(--nr-border)}.capabilities{grid-template-columns:1fr;gap:2rem}}@media(max-width:700px){.utility-main{min-height:0}.utility-shell{padding-top:1.5rem}.utility-intro h1{font-size:clamp(2.4rem,12vw,3.4rem)}.utility-summary{grid-template-columns:1fr}.utility-summary div{grid-template-columns:2.5rem 1fr;padding:.75rem 0}.utility-summary div+div{border-left:0}.utility-workspace{margin-right:-.75rem;margin-left:-.75rem}.workspace-bar{align-items:flex-start;padding:.8rem}.workspace-bar div{display:grid;gap:.1rem}.workspace-bar small{max-width:9rem;text-align:right}.tool-switch button{min-height:3.6rem;padding:0 .8rem}.tool-panel{padding:1.5rem .8rem 2rem}.tool-panel__heading{display:grid;gap:.75rem}.tool-panel__heading>span{text-align:left}.capabilities{padding:3rem 0}.capability-list li{grid-template-columns:2.5rem 1fr}.capability-list p{grid-column:2}}
</style>
<style scoped>
.footer-links{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:.75rem 1.15rem}.footer-links a{border-bottom:1px solid var(--nr-border);color:var(--nr-text);font-family:var(--nr-font-mono);font-size:.7rem;font-weight:560;text-decoration:none}@media(max-width:640px){.footer-links{justify-content:flex-start}}
</style>

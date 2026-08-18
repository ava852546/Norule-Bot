<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

const navLinks = [
  { label: '01 縮短', href: '#shorten' },
  { label: '02 媒體', href: '#media' },
  { label: '03 原則', href: '#principles' },
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
    <NrNavbar brand="NoRule URL" :links="navLinks" @navigate="handleNavigation" />

    <main>
      <NrPageContainer>
        <section class="hero" aria-labelledby="hero-title">
          <div class="hero__index" aria-hidden="true">
            <span>NR / URL</span>
            <strong>01</strong>
            <i />
            <small>LINK OPERATIONS</small>
          </div>

          <div class="hero__copy">
            <p class="hero__kicker">一個直接的分享工具</p>
            <h1 id="hero-title">把連結縮短，<br><em>把控制留下。</em></h1>
            <p class="hero__lead">建立容易傳送、容易辨識的短網址；或讓圖片與影片在你設定的時間內被看見。</p>
            <div class="hero__facts" aria-label="服務特色摘要">
              <span><b>00</b> 免登入</span>
              <span><b>01</b> 自訂短碼</span>
              <span><b>02</b> 到期與密碼</span>
            </div>
          </div>

          <div class="hero__tool">
            <div class="tool-switch" role="tablist" aria-label="選擇分享工具">
              <button id="shorten-tab" type="button" role="tab" :aria-selected="activeTool === 'shorten'" aria-controls="shorten" :tabindex="activeTool === 'shorten' ? 0 : -1" @click="selectTool('shorten')" @keydown.right.prevent="selectTool('media')"><span>01</span>縮短網址</button>
              <button id="media-tab" type="button" role="tab" :aria-selected="activeTool === 'media'" aria-controls="media" :tabindex="activeTool === 'media' ? 0 : -1" @click="selectTool('media')" @keydown.left.prevent="selectTool('shorten')"><span>02</span>媒體分享</button>
            </div>

            <div id="shorten" v-show="activeTool === 'shorten'" class="tool-panel" role="tabpanel" aria-labelledby="shorten-tab">
              <header class="tool-panel__heading">
                <p>SHORT LINK / 01</p>
                <div><h2>貼上完整網址。</h2><span>短碼可以留白；需要辨識度時再命名。</span></div>
              </header>
              <ShortUrlForm />
            </div>

            <div id="media" v-show="activeTool === 'media'" class="tool-panel" role="tabpanel" aria-labelledby="media-tab">
              <header class="tool-panel__heading">
                <p>MEDIA DROP / 02</p>
                <div><h2>選擇媒體與存取期限。</h2><span>上傳完成後，連結會清楚標示到期時間。</span></div>
              </header>
              <MediaShareForm />
            </div>
          </div>
        </section>

        <section id="principles" class="principles" aria-labelledby="principles-title">
          <header class="principles__heading">
            <p>03 / SHARING RULES</p>
            <h2 id="principles-title">每一步都有理由，<br>沒有多餘流程。</h2>
          </header>
          <ol class="principle-list">
            <li v-for="principle in principles" :key="principle.number">
              <span>{{ principle.number }}</span>
              <h3>{{ principle.title }}</h3>
              <p>{{ principle.description }}</p>
            </li>
          </ol>
        </section>

        <section class="closing" aria-labelledby="closing-title">
          <p>READY WHEN YOU ARE</p>
          <h2 id="closing-title">下一條連結，<br>不必那麼長。</h2>
          <a href="#shorten" @click="selectTool('shorten', false)">回到縮短工具 <span aria-hidden="true">↗</span></a>
        </section>
      </NrPageContainer>
    </main>

    <NrFooter brand="NoRule URL" description="短網址與限時媒體分享，為每一次傳送保留剛好的控制。"><a href="#top">TOP / 00</a></NrFooter>
  </div>
</template>

<style scoped>
.home-page{position:relative}.hero{display:grid;min-height:calc(100vh - 4.55rem);grid-template-columns:minmax(5rem,.55fr) minmax(20rem,1fr) minmax(28rem,1.35fr);align-items:start;border-right:1px solid var(--nr-border);border-left:1px solid var(--nr-border)}.hero__index{display:grid;min-height:100%;grid-template-rows:auto auto 1fr auto;gap:1.4rem;padding:2rem 1.2rem;border-right:1px solid var(--nr-border);font-family:var(--nr-font-mono)}.hero__index span,.hero__index small,.hero__kicker,.tool-panel__heading>p,.principles__heading>p,.closing>p{margin:0;color:var(--nr-text-muted);font-family:var(--nr-font-mono);font-size:.65rem;font-weight:700;letter-spacing:.08em}.hero__index strong{font-family:var(--nr-font-display);font-size:clamp(3rem,7vw,6.5rem);font-weight:650;line-height:.8}.hero__index i{width:1px;justify-self:center;background:var(--nr-border)}.hero__index small{align-self:end;writing-mode:vertical-rl}.hero__copy{display:grid;align-content:start;gap:1.5rem;padding:clamp(3rem,8vw,7rem) clamp(2rem,5vw,5.2rem) 3rem}.hero__kicker{color:var(--nr-accent);text-transform:uppercase}.hero h1{margin:0;font-family:var(--nr-font-display);font-size:clamp(3.8rem,7.4vw,7.5rem);font-weight:680;line-height:.84;letter-spacing:-.055em}.hero h1 em{color:var(--nr-accent);font-style:normal}.hero__lead{max-width:34rem;margin:0;color:var(--nr-text-muted);font-size:clamp(1rem,1.4vw,1.18rem);line-height:1.75}.hero__facts{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));margin-top:clamp(1rem,5vh,4rem);border-top:1px solid var(--nr-border);border-bottom:1px solid var(--nr-border)}.hero__facts span{display:grid;gap:.35rem;padding:.9rem .75rem;color:var(--nr-text-muted);font-size:.7rem}.hero__facts span+span{border-left:1px solid var(--nr-border)}.hero__facts b{color:var(--nr-text);font-family:var(--nr-font-mono);font-size:.62rem}.hero__tool{min-width:0;align-self:stretch;border-left:1px solid var(--nr-border-strong);background:var(--nr-surface)}.tool-switch{display:grid;grid-template-columns:1fr 1fr;border-bottom:1px solid var(--nr-border-strong)}.tool-switch button{display:flex;min-height:4.55rem;align-items:center;gap:.75rem;padding:0 1.5rem;border:0;color:var(--nr-text-muted);background:transparent;font-family:var(--nr-font-display);font-weight:700;text-align:left;cursor:pointer}.tool-switch button+button{border-left:1px solid var(--nr-border)}.tool-switch button span{font-family:var(--nr-font-mono);font-size:.62rem}.tool-switch button[aria-selected="true"]{color:var(--nr-accent-ink);background:var(--nr-text)}.tool-panel{padding:clamp(2rem,5vw,4.5rem)}.tool-panel__heading{display:grid;grid-template-columns:8rem minmax(0,1fr);gap:1.5rem;margin-bottom:2.5rem}.tool-panel__heading>p{padding-top:.45rem;color:var(--nr-accent)}.tool-panel__heading h2{margin:0;font-family:var(--nr-font-display);font-size:clamp(2rem,3.6vw,3.7rem);font-weight:680;line-height:.95;letter-spacing:-.035em}.tool-panel__heading span{display:block;max-width:28rem;margin-top:.7rem;color:var(--nr-text-muted);font-size:.82rem}.principles{display:grid;grid-template-columns:minmax(16rem,.8fr) minmax(0,1.2fr);gap:clamp(2rem,7vw,7rem);padding:clamp(5rem,10vw,9rem) 0;border-top:1px solid var(--nr-border-strong)}.principles__heading{align-self:start;position:sticky;top:7rem}.principles__heading>p{color:var(--nr-accent)}.principles__heading h2{margin:1.4rem 0 0;font-family:var(--nr-font-display);font-size:clamp(3rem,6vw,6rem);font-weight:670;line-height:.9;letter-spacing:-.045em}.principle-list{margin:0;padding:0;border-top:1px solid var(--nr-border-strong);list-style:none}.principle-list li{display:grid;grid-template-columns:4rem minmax(9rem,.65fr) minmax(14rem,1fr);gap:1.3rem;align-items:baseline;padding:1.7rem 0;border-bottom:1px solid var(--nr-border)}.principle-list span{font-family:var(--nr-font-mono);font-size:.68rem}.principle-list h3{margin:0;font-family:var(--nr-font-display);font-size:1.35rem}.principle-list p{margin:0;color:var(--nr-text-muted);font-size:.88rem}.closing{display:grid;grid-template-columns:minmax(8rem,.45fr) minmax(18rem,1fr) auto;align-items:end;gap:2rem;padding:clamp(4rem,8vw,7rem) 0;border-top:1px solid var(--nr-border-strong)}.closing>p{align-self:start;color:var(--nr-accent)}.closing h2{margin:0;font-family:var(--nr-font-display);font-size:clamp(3.5rem,8vw,8rem);font-weight:670;line-height:.82;letter-spacing:-.055em}.closing a{display:flex;min-height:3.4rem;align-items:center;gap:1rem;padding:0 1rem;border:1px solid var(--nr-border-strong);font-family:var(--nr-font-display);font-size:.86rem;font-weight:700;text-decoration:none}.closing a:hover{color:var(--nr-accent-ink);background:var(--nr-text)}@media(max-width:1120px){.hero{grid-template-columns:5rem minmax(0,1fr)}.hero__tool{grid-column:1/-1;border-top:1px solid var(--nr-border-strong);border-left:0}.hero__copy{min-height:60vh}.principles{grid-template-columns:1fr}.principles__heading{position:static}.closing{grid-template-columns:1fr auto}.closing>p{grid-column:1/-1}}@media(max-width:700px){.hero{display:block;min-height:0;border:0}.hero__index{display:none}.hero__copy{min-height:0;padding:4rem 0 3rem}.hero h1{font-size:clamp(3.7rem,17vw,5.6rem)}.hero__facts{grid-template-columns:1fr}.hero__facts span{grid-template-columns:2rem 1fr}.hero__facts span+span{border-top:1px solid var(--nr-border);border-left:0}.hero__tool{margin-right:-.75rem;margin-left:-.75rem;border-right:1px solid var(--nr-border-strong);border-left:1px solid var(--nr-border-strong)}.tool-switch button{min-height:4rem;padding:0 .8rem}.tool-panel{padding:2rem .8rem 2.5rem}.tool-panel__heading{grid-template-columns:1fr;gap:.7rem}.principles{padding:5rem 0}.principles__heading h2{font-size:clamp(3rem,14vw,4.7rem)}.principle-list li{grid-template-columns:2.5rem 1fr;padding:1.3rem 0}.principle-list p{grid-column:2}.closing{grid-template-columns:1fr;align-items:start}.closing h2{font-size:clamp(4rem,18vw,6rem)}.closing a{width:max-content}}
</style>

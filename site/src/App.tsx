import { useState } from 'react'
import { ArrowDownToLine, ArrowRight, AudioLines, Check, ChevronDown, Download, Github, Menu, Music2, ShieldCheck, Sparkles, X } from 'lucide-react'
import PhoneDemo from './PhoneDemo'

const repository = 'https://github.com/sakura-jay/Lyra-music'

function LyraLogo() {
  return <a className="brand" href="#top" aria-label="Lyra 首页"><span className="brand-mark"><AudioLines size={23} strokeWidth={2.4} /></span><span>Lyra</span></a>
}

function App() {
  const [menuOpen, setMenuOpen] = useState(false)
  const [openFaq, setOpenFaq] = useState(0)
  const faqs = [
    ['Lyra 自带音乐吗？', '不自带。Lyra 是播放器与音源宿主，不提供、不存储、不分发音频内容。你可以导入可用的 JavaScript 音源，也可以直接播放手机中的本地音乐。'],
    ['我的收藏和歌单保存在哪里？', '全部保存在你的设备上，不需要注册账号。你可以随时导出 JSON 备份；更换设备或重装应用后，再导入恢复。备份不包含音源。'],
    ['我的手机可以使用吗？', 'Lyra 支持 Android 12 及以上版本，目前专注手机竖屏体验。前往 GitHub Releases 下载 APK，按系统提示完成安装即可。'],
  ]

  return (
    <>
      <header className="site-header">
        <LyraLogo />
        <nav className={menuOpen ? 'open' : ''} aria-label="主导航">
          <a href="#features" onClick={() => setMenuOpen(false)}>为什么选择 Lyra</a>
          <a href="#whats-new" onClick={() => setMenuOpen(false)}>新功能</a>
          <a href="#faq" onClick={() => setMenuOpen(false)}>常见问题</a>
          <a className="nav-github" href={repository} target="_blank" rel="noreferrer"><Github size={17} />GitHub</a>
        </nav>
        <a className="header-download" href={`${repository}/releases/latest`} target="_blank" rel="noreferrer">下载 App<ArrowDownToLine size={16} /></a>
        <button className="menu-toggle" aria-label={menuOpen ? '关闭导航' : '打开导航'} onClick={() => setMenuOpen(!menuOpen)}>{menuOpen ? <X /> : <Menu />}</button>
      </header>

      <main id="top">
        <section className="hero">
          <div className="hero-glow" aria-hidden="true" />
          <div className="hero-copy">
            <div className="eyebrow"><span /> Android 原生音乐播放器</div>
            <h1>把音乐，<br />还给播放器。</h1>
            <p className="hero-description">不被一个平台定义，也不让数据困住你。<br />多音源、沉浸歌词、本地音乐库，<br className="mobile-break" />让每一次播放都回归纯粹。</p>
            <div className="hero-actions"><a className="button button-primary" href={`${repository}/releases/latest`} target="_blank" rel="noreferrer"><Download size={18} />下载最新版本<ArrowRight size={17} /></a><a className="button button-secondary" href={repository} target="_blank" rel="noreferrer"><Github size={18} />在 GitHub 了解</a></div>
            <div className="hero-meta"><span><Check size={14} />Android 12+</span><span><Check size={14} />无需账号</span><span><Check size={14} />开源免费</span></div>
          </div>
          <PhoneDemo />
          <a className="scroll-cue" href="#features" aria-label="向下探索"><span>继续探索</span><ChevronDown size={17} /></a>
        </section>

        <section className="features section" id="features">
          <div className="section-intro"><span className="section-kicker">为听音乐的人而做</span><h2>你关心的，<br />Lyra 认真做到了。</h2><p>好的播放器不该打扰音乐。<br />它应该理解你的选择，然后安静地做好一切。</p></div>
          <div className="feature-stories">
            <article className="feature-story">
              <div className="feature-copy"><span className="feature-icon"><AudioLines size={23} /></span><h3>一处搜索，<br />不止一种答案。</h3><p>同时启用多个音源，聚合搜索想听的歌。播放失败时自动尝试其他来源，实际音质也如实呈现。</p><div className="text-tag">多音源 · 自动换源 · 无损音质</div></div>
              <div className="source-visual" aria-label="聚合搜索示意图"><div className="search-field"><Music2 size={17} /><span>搜索一首歌</span><span className="search-shortcut">搜索</span></div><div className="result-row"><div className="result-art result-art--one" /><div><strong>夜航星</strong><span>你的私人电台</span></div><span className="quality">FLAC</span></div><div className="result-row result-row--active"><div className="result-art result-art--two" /><div><strong>夜航星</strong><span>来自音源 B · 已自动换源</span></div><span className="playing-bars"><i /><i /><i /></span></div><div className="result-row"><div className="result-art result-art--three" /><div><strong>夜航星 (Live)</strong><span>来自音源 A</span></div><span className="quality">320k</span></div><div className="source-path"><span /><p>找不到播放地址？<strong>为你试试其他音源</strong></p></div></div>
            </article>

            <article className="feature-story feature-story--reverse">
              <div className="feature-copy"><span className="feature-icon"><ShieldCheck size={23} /></span><h3>没有账号，<br />音乐依然属于你。</h3><p>喜欢、歌单和设置都留在本机。扫描本地音乐，或导出完整备份——你的音乐生活，不必交给云端。</p><div className="text-tag">本地音乐 · 自建歌单 · JSON 备份</div></div>
              <div className="library-visual"><div className="vinyl"><div className="vinyl-center"><AudioLines size={25} /></div></div><div className="local-card"><span className="local-card-label">我的音乐库</span><strong>一切珍藏，<br />都在这里。</strong><div className="library-count"><span>本地歌曲</span><b>128</b></div><div className="library-count"><span>我的歌单</span><b>03</b></div><div className="offline-pill"><ShieldCheck size={13} />只保存在这台设备</div></div></div>
            </article>
          </div>
        </section>

        <section className="whats-new section" id="whats-new">
          <div className="new-panel">
            <div className="new-copy"><div className="version-pill"><Sparkles size={14} />V0.10.0 · 正在开发</div><h2>更通透，<br />也更省心。</h2><p>玻璃材质让内容自然浮现，应用内更新让每次进步轻松抵达。</p><a href={`${repository}/tree/main/docs/releases/v0.10.0`} target="_blank" rel="noreferrer">查看开发计划<ArrowRight size={15} /></a></div>
            <div className="glass-preview"><div className="glass-preview-bg"><span /><span /><span /></div><div className="glass-window"><div className="glass-window-top"><AudioLines size={18} /><span>发现音乐</span><SearchIcon /></div><div className="glass-window-body"><div><small>为你推荐</small><strong>今天，想听点什么？</strong></div><div className="glass-covers"><i /><i /><i /></div></div><div className="glass-player"><div className="mini-art" /><span>晚风来信<small>正在播放</small></span><AudioLines size={20} /></div></div></div>
            <div className="update-note"><span className="update-icon"><ArrowDownToLine size={22} /></span><div><strong>更新，不必再到处找</strong><p>自动检测 GitHub 新版本，应用内下载并校验 APK，再交由系统确认安装。</p></div><span className="dev-label">开发中</span></div>
          </div>
        </section>

        <section className="faq section" id="faq">
          <div className="section-intro"><span className="section-kicker">开始之前</span><h2>一些你可能会问的事。</h2></div>
          <div className="faq-list">{faqs.map(([question, answer], index) => <div className={`faq-item ${openFaq === index ? 'open' : ''}`} key={question}><button aria-expanded={openFaq === index} onClick={() => setOpenFaq(openFaq === index ? -1 : index)}><span>{question}</span><PlusIcon open={openFaq === index} /></button><div className="faq-answer"><p>{answer}</p></div></div>)}</div>
        </section>

        <section className="closing section"><div className="closing-record" aria-hidden="true"><AudioLines size={60} /></div><span className="section-kicker">下一段旋律，由你决定</span><h2>留一点时间，<br />好好听音乐。</h2><a className="button button-primary" href={`${repository}/releases/latest`} target="_blank" rel="noreferrer"><Download size={18} />下载 Lyra<ArrowRight size={17} /></a><p>Android 12 及以上 · 从 GitHub Releases 安全下载</p></section>
      </main>
      <footer><LyraLogo /><p>把音乐还给播放器。</p><div><a href={repository} target="_blank" rel="noreferrer">GitHub</a><a href={`${repository}/issues`} target="_blank" rel="noreferrer">问题反馈</a><a href={`${repository}/blob/main/CHANGELOG.md`} target="_blank" rel="noreferrer">更新日志</a></div><span>Lyra 不提供或分发任何音频内容。</span></footer>
    </>
  )
}

function PlusIcon({ open }: { open: boolean }) {
  return <span className={`plus-icon ${open ? 'rotated' : ''}`}><span /><span /></span>
}

function SearchIcon() {
  return <span className="tiny-search" />
}

export default App

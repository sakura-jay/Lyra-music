import { useEffect, useState } from 'react'
import {
  ArrowDownToLine, ArrowLeft, Check, ChevronRight, Disc3, FolderHeart,
  Heart, ListMusic, MoreHorizontal, Music2, Pause, Play, Plus, Repeat2,
  Search, Shuffle, SkipBack, SkipForward, SlidersHorizontal, Wifi,
} from 'lucide-react'

type View = 'lyrics' | 'library' | 'sources'

const tracks = [
  ['晴天以后', '本地音乐 · FLAC', '03:42'],
  ['城市漫游', '我喜欢 · 320k', '04:16'],
  ['晚风来信', '我的歌单 · Hi-Res', '03:58'],
  ['银河渡口', '本地音乐 · FLAC', '04:25'],
]

function AlbumArt({ small = false }: { small?: boolean }) {
  return (
    <div className={`album-art ${small ? 'album-art--small' : ''}`} aria-label="抽象唱片封面插画" role="img">
      <div className="album-sun" />
      <div className="album-orbit album-orbit--one" />
      <div className="album-orbit album-orbit--two" />
      <span>LYRA</span>
    </div>
  )
}

function Lyrics({ playing, setPlaying }: { playing: boolean; setPlaying: (value: boolean) => void }) {
  const [activeLine, setActiveLine] = useState(2)
  const lyrics = ['当夜色漫过窗台', '城市慢慢静下来', '把旋律留给自己', '让此刻 只属于现在']

  return (
    <div className="lyrics-view">
      <div className="app-bar"><ArrowLeft size={18} /><span>正在播放</span><MoreHorizontal size={19} /></div>
      <div className="lyrics-track"><AlbumArt small /><div><strong>霓虹夜行</strong><span>午夜电台 · FLAC</span></div><Heart size={17} /></div>
      <div className="lyrics-lines">
        {lyrics.map((line, index) => (
          <button key={line} className={activeLine === index ? 'active' : ''} onClick={() => setActiveLine(index)}>{line}</button>
        ))}
      </div>
      <div className="player-controls">
        <div className="progress-track"><span style={{ width: `${32 + activeLine * 14}%` }} /></div>
        <div className="time-row"><span>01:24</span><span>03:56</span></div>
        <div className="control-row"><Repeat2 size={16} /><SkipBack size={22} fill="currentColor" /><button className="play-button" onClick={() => setPlaying(!playing)} aria-label={playing ? '暂停' : '播放'}>{playing ? <Pause size={22} fill="currentColor" /> : <Play size={22} fill="currentColor" />}</button><SkipForward size={22} fill="currentColor" /><SlidersHorizontal size={16} /></div>
      </div>
    </div>
  )
}

function Library() {
  const [liked, setLiked] = useState(false)
  return (
    <div className="library-view">
      <div className="app-bar"><span className="app-title">音乐库</span><Search size={18} /></div>
      <div className="library-heading"><span>留在这里的，<br />都是你的音乐。</span><div className="library-avatar">L</div></div>
      <div className="library-shortcuts"><div><Heart size={19} /><span>我喜欢<small>26 首歌曲</small></span><ChevronRight size={14} /></div><div><FolderHeart size={19} /><span>我的歌单<small>3 个歌单</small></span><ChevronRight size={14} /></div></div>
      <div className="section-label"><span>最近播放</span><small>全部</small></div>
      <div className="track-list">
        {tracks.map(([name, meta, time], index) => <div className="track" key={name}><div className={`track-cover track-cover--${index + 1}`}><Music2 size={15} /></div><div><strong>{name}</strong><span>{meta}</span></div><span className="track-time">{time}</span></div>)}
      </div>
      <button className={`backup-button ${liked ? 'done' : ''}`} onClick={() => setLiked(!liked)}>{liked ? <Check size={15} /> : <ArrowDownToLine size={15} />}{liked ? '备份已导出' : '导出我的音乐库'}</button>
    </div>
  )
}

function Sources() {
  const [enabled, setEnabled] = useState([true, true])
  const [imported, setImported] = useState(false)
  return (
    <div className="sources-view">
      <div className="app-bar"><span className="app-title">音源管理</span><button className="icon-button" aria-label="添加音源" onClick={() => setImported(true)}><Plus size={19} /></button></div>
      <div className="source-intro"><div className="source-symbol"><Disc3 size={29} /></div><h3>不止一种可能。</h3><p>导入音源，让音乐来自你喜欢的地方。</p></div>
      <div className="source-list">
        {['我的音源 A', '我的音源 B'].map((source, index) => <div className="source-item" key={source}><div className="source-icon"><Wifi size={18} /></div><div><strong>{source}</strong><span><i /> 已连接 · 搜索与播放</span></div><button className={`switch ${enabled[index] ? 'on' : ''}`} role="switch" aria-checked={enabled[index]} aria-label={`启用${source}`} onClick={() => setEnabled(enabled.map((value, i) => i === index ? !value : value))}><span /></button></div>)}
      </div>
      <div className="source-note"><Check size={14} /><span>播放失败时，可自动尝试其他音源</span></div>
      <div className="source-footer"><ListMusic size={16} /><span>音源由你导入，选择权始终在你</span></div>
      {imported && <button className="demo-toast" onClick={() => setImported(false)}><Check size={15} /> 可从文件或链接导入音源</button>}
    </div>
  )
}

export default function PhoneDemo() {
  const [view, setView] = useState<View>('lyrics')
  const [playing, setPlaying] = useState(true)
  const [seconds, setSeconds] = useState(0)

  useEffect(() => {
    if (!playing) return
    const timer = window.setInterval(() => setSeconds((value) => value + 1), 1000)
    return () => window.clearInterval(timer)
  }, [playing])

  return (
    <div className="demo-wrap">
      <div className="demo-orbit" aria-hidden="true" />
      <div className="phone-shell">
        <div className="phone-screen">
          <div className="status-bar"><span>21:08</span><div><span className="signal" /><Wifi size={12} /><span className="battery" /></div></div>
          {view === 'lyrics' && <Lyrics playing={playing} setPlaying={setPlaying} />}
          {view === 'library' && <Library />}
          {view === 'sources' && <Sources />}
          <div className="phone-nav">
            {([['lyrics', Disc3, '沉浸歌词'], ['library', FolderHeart, '音乐库'], ['sources', Wifi, '多音源']] as const).map(([name, Icon, label]) => <button key={name} className={view === name ? 'active' : ''} onClick={() => setView(name)}><Icon size={18} /><span>{label}</span></button>)}
          </div>
          <div className="home-indicator" />
        </div>
      </div>
      <div className="demo-caption"><span className={playing ? 'live-dot' : 'live-dot paused'} /><span>{playing ? `界面演示 · 正在播放 ${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}` : '界面演示 · 已暂停'}</span><span>点击手机，亲自体验</span></div>
      <div className="floating-chip floating-chip--top"><Shuffle size={15} /><span>聚合搜索</span></div>
      <div className="floating-chip floating-chip--bottom"><Heart size={15} /><span>只保存在你的设备</span></div>
    </div>
  )
}

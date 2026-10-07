/*
 * Lyra 的音源宿主契约（lx.*）实现。
 *
 * 协议与洛雪移动端对齐：脚本通过 globalThis.lx 拿能力，宿主通过
 * __lx_native__(action, dataJson) 打进来说话，脚本通过
 * __lx_native_call__(key, action, dataJson) 回来说话。
 *
 * 相比官方移动版，这里补齐了它注释掉的 zlib 与官方没有的 sha/des，
 * 使 Lyra 成为契约超集；缺失只会让音源报错，多给不会。
 */
globalThis.lx_setup = (key, id, name, description, version, author, homepage, rawScript) => {
  const _nativeCall = globalThis.__lx_native_call__
  delete globalThis.__lx_native_call__

  const nativeFuncNames = [
    '__lx_native_call__set_timeout',
    '__lx_native_call__clear_timeout',
    '__lx_native_call__utils_str2b64',
    '__lx_native_call__utils_buf2b64',
    '__lx_native_call__utils_b642buf',
    '__lx_native_call__utils_str2md5',
    '__lx_native_call__utils_sha',
    '__lx_native_call__utils_random_bytes',
    '__lx_native_call__utils_aes_encrypt',
    '__lx_native_call__utils_aes_decrypt',
    '__lx_native_call__utils_des_crypt',
    '__lx_native_call__utils_rsa_encrypt',
    '__lx_native_call__utils_zlib',
  ]
  const nativeFuncs = {}
  for (const fname of nativeFuncNames) {
    const fn = globalThis[fname]
    delete globalThis[fname]
    nativeFuncs[fname.replace('__lx_native_call__', '')] = fn
  }

  const NATIVE_EVENTS = {
    init: 'init',
    showUpdateAlert: 'showUpdateAlert',
    request: 'request',
    cancelRequest: 'cancelRequest',
    response: 'response',
    log: 'log',
  }
  const EVENT_NAMES = { request: 'request', inited: 'inited', updateAlert: 'updateAlert' }
  const RSA_PADDING = { NOPAD: 'RSA/ECB/NoPadding', OAEP: 'RSA/ECB/OAEPWithSHA1AndMGF1Padding', PKCS1: 'RSA/ECB/PKCS1Padding' }
  const KEY_PREFIX = {
    publicStart: '-----BEGIN PUBLIC KEY-----',
    publicEnd: '-----END PUBLIC KEY-----',
    privateStart: '-----BEGIN PRIVATE KEY-----',
    privateEnd: '-----END PRIVATE KEY-----',
  }

  const callNative = (action, data) => _nativeCall(key, action, JSON.stringify(data))

  // ---------- buffer：值用 Uint8Array 表达，与官方一致 ----------
  const utf8Encode = (str) => {
    const out = []
    for (let i = 0; i < str.length; i++) {
      let c = str.charCodeAt(i)
      if (c < 0x80) out.push(c)
      else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63))
      else if (c >= 0xd800 && c < 0xe000) {
        const next = str.charCodeAt(++i)
        c = 0x10000 + ((c & 0x03ff) << 10) + (next & 0x03ff)
        out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63))
      } else out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63))
    }
    return out
  }
  const utf8Decode = (bytes) => {
    let out = ''
    for (let i = 0; i < bytes.length;) {
      const b = bytes[i++]
      if (b < 0x80) out += String.fromCharCode(b)
      else if (b >= 0xc0 && b < 0xe0) out += String.fromCharCode(((b & 31) << 6) | (bytes[i++] & 63))
      else if (b >= 0xe0 && b < 0xf0) out += String.fromCharCode(((b & 15) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63))
      else {
        const cp = ((b & 7) << 18) | ((bytes[i++] & 63) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63)
        const v = cp - 0x10000
        out += String.fromCharCode(0xd800 + (v >> 10), 0xdc00 + (v & 0x3ff))
      }
    }
    return out
  }
  const toBytes = (v) => (Array.isArray(v) ? v : ArrayBuffer.isView(v) ? Array.from(v) : Array.from(v))

  /**
   * 无损取 base64：字符串按 UTF-8 编码，字节数组直接转。
   *
   * 官方 preload 把两种输入都绕经 bytesToString/utf8Decode，任意二进制（典型是 AES
   * 密文）会被当成 UTF-8 破坏，导致解密报 WRONG_BLOCK_LENGTH。Lyra 不沿用这个缺陷。
   */
  const b64Of = (value) => typeof value === 'string'
    ? nativeFuncs.utils_str2b64(value)
    : nativeFuncs.utils_buf2b64(JSON.stringify(toBytes(value)))

  /**
   * 音源传的是 'aes-128-cbc' 这类字面量（官方协议），不是 JCE 变换名。
   * 之前拿 AES_MODE 的变换名去比，永远不相等，CBC 全被当成 ECB 跑了。
   */
  const aesModeOf = (mode) => {
    const m = String(mode || '').toLowerCase().replace(/_/g, '-')
    if (m.endsWith('-cbc') || m === 'cbc') return 'cbc'
    if (m.endsWith('-ecb') || m === 'ecb') return 'ecb'
    throw new Error('Unsupported AES mode: ' + mode)
  }

  const utils = {
    buffer: {
      from(input, encoding) {
        if (typeof input === 'string') {
          switch (encoding) {
            case 'base64': return new Uint8Array(JSON.parse(nativeFuncs.utils_b642buf(input)))
            case 'hex': return new Uint8Array(input.match(/.{1,2}/g).map((b) => parseInt(b, 16)))
            case 'binary': throw new Error('Binary encoding is not supported for input strings')
            default: return new Uint8Array(utf8Encode(input))
          }
        }
        if (Array.isArray(input) || ArrayBuffer.isView(input)) return new Uint8Array(input)
        throw new Error('Unsupported input type: ' + input + ' encoding: ' + encoding)
      },
      bufToString(buf, format) {
        if (buf == null) return ''
        if (typeof buf === 'string') {
          // 允许直接对 base64/hex 句柄调用，宽容处理
          if (format === 'base64') return buf
          return utils.buffer.bufToString(utils.buffer.from(buf, format || 'utf-8'), format)
        }
        const bytes = toBytes(buf)
        switch (format) {
          case 'hex': return bytes.reduce((s, b) => s + b.toString(16).padStart(2, '0'), '')
          case 'base64': return nativeFuncs.utils_buf2b64(JSON.stringify(bytes))
          case 'binary': return buf
          default: return utf8Decode(bytes)
        }
      },
    },
    crypto: {
      aesEncrypt(buffer, mode, secret, iv) {
        const alg = aesModeOf(mode)
        return utils.buffer.from(
            nativeFuncs.utils_aes_encrypt(b64Of(buffer), b64Of(secret), iv == null ? '' : b64Of(iv), alg),
            'base64',
        )
      },
      aesDecrypt(buffer, mode, secret, iv) {
        const alg = aesModeOf(mode)
        return utils.buffer.from(
            nativeFuncs.utils_aes_decrypt(b64Of(buffer), b64Of(secret), iv == null ? '' : b64Of(iv), alg),
            'base64',
        )
      },
      desEncrypt(buffer, secret, mode = 'des-cbc') {
        return utils.buffer.from(nativeFuncs.utils_des_crypt(b64Of(buffer), b64Of(secret), mode, true), 'base64')
      },
      desDecrypt(buffer, secret, mode = 'des-cbc') {
        return utils.buffer.from(nativeFuncs.utils_des_crypt(b64Of(buffer), b64Of(secret), mode, false), 'base64')
      },
      rsaEncrypt(buffer, key) {
        if (typeof key !== 'string') throw new Error('Invalid RSA key')
        const cleanKey = key.replace(KEY_PREFIX.publicStart, '').replace(KEY_PREFIX.publicEnd, '')
          .replace(KEY_PREFIX.privateStart, '').replace(KEY_PREFIX.privateEnd, '')
        return utils.buffer.from(nativeFuncs.utils_rsa_encrypt(b64Of(buffer), cleanKey, RSA_PADDING.NOPAD), 'base64')
      },
      md5(str) {
        if (typeof str !== 'string') throw new Error('param required a string')
        return nativeFuncs.utils_str2md5(encodeURIComponent(str))
      },
      sha(str, algorithm = 'sha1') {
        if (typeof str !== 'string') throw new Error('param required a string')
        return nativeFuncs.utils_sha(str, algorithm)
      },
      randomBytes(size) {
        const arr = new Uint8Array(size)
        for (let i = 0; i < size; i++) arr[i] = Math.floor(Math.random() * 256)
        return arr
      },
    },
    zlib: {
      // 官方移动版注释掉了这两项，Lyra 补上，成为契约超集。
      // 入参必须经 b64Of：Kotlin 侧按 base64 取字节，直接塞原始字符串会被误解码。
      inflate(buffer) {
        return utils.buffer.from(nativeFuncs.utils_zlib(b64Of(buffer), 'inflate'), 'base64')
      },
      deflate(buffer) {
        return utils.buffer.from(nativeFuncs.utils_zlib(b64Of(buffer), 'deflate'), 'base64')
      },
    },
  }

  // ---------- setTimeout ----------
  const timers = new Map()
  let timerSeq = 0
  const _setTimeout = (fn, timeout = 0, ...args) => {
    const tid = String(++timerSeq)
    timers.set(tid, () => { timers.delete(tid); fn(...args) })
    nativeFuncs.set_timeout(tid, Number(timeout))
    return tid
  }
  const _clearTimeout = (tid) => {
    const key = String(tid)
    if (!timers.has(key)) return
    timers.delete(key)
    nativeFuncs.clear_timeout(key)
  }
  const handleSetTimeout = ({ id }) => {
    const task = timers.get(String(id))
    if (!task) return
    task()
  }

  // ---------- 请求 ----------
  const pending = new Map()
  let reqSeq = 0
  const doRequest = (url, options = {}) => {
    const requestKey = 'r' + (++reqSeq) + '-' + Math.random().toString(36).slice(2, 8)
    const promise = new Promise((resolve, reject) => {
      pending.set(requestKey, { resolve, reject, url })
      try {
        callNative(NATIVE_EVENTS.request, { requestKey, url, options })
      } catch (err) {
        pending.delete(requestKey)
        reject(err)
      }
    }).then((response) => {
      if (options.binary && response.body) response.body = new Uint8Array(response.body)
      return response
    })
    return { requestKey, promise }
  }

  const handleNativeResponse = ({ requestKey, error, response }) => {
    const target = pending.get(requestKey)
    if (!target) return
    pending.delete(requestKey)
    if (error == null) target.resolve(response)
    else target.reject(new Error(error))
  }

  // 同时支持 callback 风格与 Promise 风格：真实音源两种都在用
  const request = (url, options = {}, callback) => {
    const { requestKey, promise } = doRequest(url, options)
    const handle = {
      aborted: false,
      abort() {
        if (this.aborted) return
        this.aborted = true
        if (pending.delete(requestKey)) callNative(NATIVE_EVENTS.cancelRequest, { requestKey })
      },
      promise,
    }
    if (callback) {
      promise.then(
        (resp) => callback(null, resp),
        (err) => callback(err, null),
      )
    }
    return handle
  }

  // ---------- 事件 ----------
  const events = { request: null }
  let initedSent = false

  const send = (eventName, data = {}) => new Promise((resolve, reject) => {
    switch (eventName) {
      case EVENT_NAMES.inited:
        if (initedSent) return reject(new Error('Script is already inited'))
        initedSent = true
        callNative(NATIVE_EVENTS.init, data)
        return resolve()
      case EVENT_NAMES.updateAlert:
        callNative(NATIVE_EVENTS.showUpdateAlert, {
          log: typeof data.log === 'string' ? data.log : JSON.stringify(data.log ?? ''),
          updateUrl: data.updateUrl,
          name,
        })
        return resolve()
      default:
        return reject(new Error('Unknown event name: ' + eventName))
    }
  })

  const on = (eventName, handler) => {
    if (eventName !== EVENT_NAMES.request) return Promise.reject(new Error('The event is not supported: ' + eventName))
    events.request = handler
    return Promise.resolve()
  }

  const verifyLyricInfo = (info) => {
    const bad = () => { throw new Error('Invalid lyric info') }
    if (typeof info !== 'object' || info == null) bad()
    if (typeof info.lyric !== 'string') bad()
    if (info.tlyric != null && typeof info.tlyric !== 'string') bad()
    if (info.lxlyric != null && typeof info.lxlyric !== 'string') bad()
    return { ...info, lyric: info.lyric, tlyric: info.tlyric || '', lxlyric: info.lxlyric || '' }
  }

  const normalizeResult = (action, info, response) => {
    switch (action) {
      case 'musicUrl':
      case 'pic':
        if (typeof response !== 'string' || response.length > 2048 || !/^https?:/.test(response)) throw new Error('failed')
        return action === 'musicUrl' ? { type: info.type, url: response } : response
      case 'lyric':
        return verifyLyricInfo(response)
      case 'songList':
        if (!Array.isArray(response.list) || typeof response.source !== 'string' || typeof response.id !== 'string') throw new Error('failed')
        return { source: response.source, id: response.id, name: response.name, time: response.time ?? 0,
          timestamp: response.timestamp ?? 0, avatar: response.avatar ?? '', description: response.description ?? '',
          playCount: response.playCount ?? 0, list: response.list }
      case 'search':
        if (!Array.isArray(response.list)) throw new Error('failed')
        return response
      default:
        throw new Error('Unknown action: ' + action)
    }
  }

  const handleRequest = ({ requestKey, data }) => {
    if (!events.request) {
      callNative(NATIVE_EVENTS.response, { requestKey, status: false, errorMessage: 'Request event is not defined' })
      return
    }
    try {
      Promise.resolve(events.request.call(globalThis.lx, { source: data.source, action: data.action, info: data.info }))
        .then((response) => {
          const result = { source: data.source, action: data.action, data: normalizeResult(data.action, data.info, response) }
          callNative(NATIVE_EVENTS.response, { requestKey, status: true, result })
        })
        .catch((err) => {
          callNative(NATIVE_EVENTS.response, { requestKey, status: false, errorMessage: err?.message ?? String(err) })
        })
    } catch (err) {
      callNative(NATIVE_EVENTS.response, { requestKey, status: false, errorMessage: err?.message ?? String(err) })
    }
  }

  const jsCall = (action, data) => {
    switch (action) {
      case '__run_error__': return
      case '__set_timeout__': return handleSetTimeout(data)
      case 'request': return handleRequest(data)
      case 'response': return handleNativeResponse(data)
      default: return 'Unknown action: ' + action
    }
  }

  Object.defineProperty(globalThis, '__lx_native__', {
    enumerable: false, configurable: false, writable: false,
    // key 校验不能省：否则音源脚本可以自己调 __lx_native__ 伪造 response 或 inited
    value: (_key, action, data) => {
      if (_key !== key) return 'Invalid key'
      return data == null ? jsCall(action) : jsCall(action, JSON.parse(data))
    },
  })

  globalThis.lx = {
    EVENT_NAMES,
    env: 'mobile',
    version: '2.0.0',
    currentScriptId: id,
    currentScriptInfo: { name, description, version, author, homepage, rawScript },
    on,
    send,
    request,
    utils,
  }

  globalThis.setTimeout = _setTimeout
  globalThis.clearTimeout = _clearTimeout
}

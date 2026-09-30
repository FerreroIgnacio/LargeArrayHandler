// Every mineflayer bot in one process, driven by the mod over one local socket (see protocol.js),
// with a pool of worker threads serializing chunks.
// Usage: node fleet.js <mod port>
const net = require('net')
const os = require('os')
const path = require('path')
const { Worker } = require('worker_threads')
const protocol = require('./protocol')
const startBot = require('./bot')

// Serialization jobs, each to the first idle worker; queued while all are busy.
class Pool {
  constructor (file, size) {
    this.idle = []
    this.queue = []
    this.running = new Map()
    this.jobs = 0
    for (let i = 0; i < size; i++) {
      const worker = new Worker(file)
      worker.on('message', ({ frame }) => {
        const resolve = this.running.get(worker)
        this.running.delete(worker)
        resolve(Buffer.from(frame.buffer, frame.byteOffset, frame.byteLength))
        this.idle.push(worker)
        this.next()
      })
      worker.on('error', err => { throw err })
      worker.on('exit', code => { throw new Error(`serializer worker exited (code ${code})`) })
      worker.unref()
      this.idle.push(worker)
    }
  }

  run (job) {
    return new Promise(resolve => {
      this.queue.push({ job: { ...job, id: this.jobs++ }, resolve })
      this.next()
    })
  }

  next () {
    while (this.idle.length && this.queue.length) {
      const worker = this.idle.pop()
      const { job, resolve } = this.queue.shift()
      this.running.set(worker, resolve)
      worker.postMessage(job)
    }
  }
}

const pool = new Pool(path.join(__dirname, 'serializer.js'), Math.max(1, os.cpus().length - 1))
const bots = new Map()
// Formation spots: "server|dimension|x,y,z" -> bot standing there, and bot -> its spot. One bot per block.
const spots = new Map()
const spotOf = new Map()
function release (name) {
  const key = spotOf.get(name)
  if (key === undefined) return
  spots.delete(key)
  spotOf.delete(name)
}
let closing = false

const socket = net.connect(Number(process.argv[2]), '127.0.0.1')
socket.setNoDelay(true)
socket.on('error', err => { throw err })

// Once closing the mod is gone: what the bots still report on their way out has nowhere to go.
const send = frame => { if (!closing) socket.write(frame) }

socket.on('data', protocol.frames(frame => {
  const message = protocol.decode(frame)
  switch (message.type) {
    case protocol.TYPES.SPAWN: {
      if (bots.has(message.bot)) throw new Error(`a bot named ${message.bot} is already running`)
      const bot = startBot({
        name: message.bot,
        host: message.host,
        port: message.port,
        send,
        serialize: job => pool.run(job),
        onEnd: () => {
          release(message.bot)
          bots.delete(message.bot)
          if (closing && bots.size === 0) process.exit(0)
        }
      })
      bots.set(message.bot, bot)
      break
    }
    case protocol.TYPES.QUIT:
      // Already gone: its BOT_GONE is on the way to the mod.
      bots.get(message.bot)?.quit()
      break
    case protocol.TYPES.REQUEST_CHUNK:
      // Same: the registry hands the chunk to another holder once BOT_GONE lands.
      bots.get(message.bot)?.sendChunk(message.key, message.claim)
      break
    case protocol.TYPES.FORMATION: {
      // Same: a bot already gone has no spot to take.
      const bot = bots.get(message.bot)
      if (!bot) break
      release(message.bot)
      const key = bot.formation(message, key => !spots.has(key))
      spots.set(key, message.bot)
      spotOf.set(message.bot, key)
      break
    }
  }
}))

// The mod going away (socket or stdin closed) takes every bot with it.
function close () {
  if (closing) return
  closing = true
  if (bots.size === 0) process.exit(0)
  for (const bot of bots.values()) bot.quit()
}

socket.on('close', close)
process.stdin.on('end', close)
process.stdin.resume()

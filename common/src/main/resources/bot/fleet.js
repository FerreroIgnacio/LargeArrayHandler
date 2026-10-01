// Every mineflayer bot in one process, spread over a pool of worker threads (poolThread.js) that
// share their chunk columns (sharedChunks.js), driven by the mod over one local socket (see protocol.js).
// Usage: node fleet.js <mod port>
const net = require('net')
const os = require('os')
const path = require('path')
const { Worker, MessageChannel } = require('worker_threads')
const protocol = require('./protocol')
const { SIZE } = require('./sharedChunks')

// Bot name -> the thread running it.
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

// The shared columns: "server|dimension|cx,cz" -> { buffer, threads holding it }. Gone once no thread does.
const columns = new Map()

function onChunkRequest (thread, request) {
  if (request.acquire !== undefined) {
    let column = columns.get(request.acquire)
    const load = !column
    if (load) columns.set(request.acquire, (column = { buffer: new SharedArrayBuffer(SIZE), threads: new Set() }))
    column.threads.add(thread)
    // The thread waits on the signal, then takes the answer straight off its port.
    thread.chunkPort.postMessage({ buffer: column.buffer, load })
    Atomics.store(thread.signal, 0, 1)
    Atomics.notify(thread.signal, 0)
  } else if (request.release !== undefined) {
    const column = columns.get(request.release)
    if (!column || !column.threads.delete(thread)) throw new Error(`a thread released column ${request.release} it does not hold`)
    if (column.threads.size === 0) columns.delete(request.release)
  } else {
    throw new Error('unknown chunk request from a pool thread')
  }
}

function onReport (thread, report) {
  if (report.frame) {
    send(Buffer.from(report.frame.buffer, report.frame.byteOffset, report.frame.byteLength))
  } else if (report.candidates) {
    // Its nearest spot no other bot stands on.
    const name = report.bot
    const taken = report.candidates.find(({ key }) => !spots.has(key) || spots.get(key) === name)
    if (!taken) throw new Error(`${name}: every block to stand on around its formation target is taken`)
    release(name)
    spots.set(taken.key, name)
    spotOf.set(name, taken.key)
    thread.worker.postMessage({ type: 'goto', bot: name, spot: taken.spot })
  } else if (report.end) {
    release(report.bot)
    bots.delete(report.bot)
    thread.bots--
    if (closing && bots.size === 0) process.exit(0)
  } else {
    throw new Error('unknown report from a pool thread')
  }
}

const threads = []
for (let i = 0; i < os.cpus().length; i++) {
  const { port1, port2 } = new MessageChannel()
  const signal = new SharedArrayBuffer(4)
  const thread = { bots: 0, chunkPort: port1, signal: new Int32Array(signal) }
  thread.worker = new Worker(path.join(__dirname, 'poolThread.js'), {
    workerData: { chunkPort: port2, signal },
    transferList: [port2]
  })
  thread.worker.on('message', report => onReport(thread, report))
  thread.worker.on('error', err => { throw err })
  thread.worker.on('exit', code => { throw new Error(`pool thread exited (code ${code})`) })
  port1.on('message', request => onChunkRequest(thread, request))
  threads.push(thread)
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
      // The thread running the fewest bots.
      const thread = threads.reduce((least, t) => t.bots < least.bots ? t : least)
      thread.bots++
      bots.set(message.bot, thread)
      thread.worker.postMessage({ type: 'spawn', name: message.bot, host: message.host, port: message.port })
      break
    }
    case protocol.TYPES.QUIT:
      // Already gone: its BOT_GONE is on the way to the mod.
      bots.get(message.bot)?.worker.postMessage({ type: 'quit', bot: message.bot })
      break
    case protocol.TYPES.REQUEST_CHUNK:
      // Same: the registry hands the chunk to another holder once BOT_GONE lands.
      bots.get(message.bot)?.worker.postMessage({ type: 'sendChunk', bot: message.bot, key: message.key, claim: message.claim })
      break
    case protocol.TYPES.FORMATION:
      // Same: a bot already gone has no spot to take. Its old spot goes once it takes the new one.
      bots.get(message.bot)?.worker.postMessage({ type: 'formation', bot: message.bot, target: { x: message.x, y: message.y, z: message.z } })
      break
  }
}))

// The mod going away (socket or stdin closed) takes every bot with it.
function close () {
  if (closing) return
  closing = true
  if (bots.size === 0) process.exit(0)
  for (const [name, thread] of bots) thread.worker.postMessage({ type: 'quit', bot: name })
}

socket.on('close', close)
process.stdin.on('end', close)
process.stdin.resume()

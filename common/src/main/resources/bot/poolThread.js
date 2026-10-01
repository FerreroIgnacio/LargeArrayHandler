// Worker thread of the fleet's pool, running some of its bots (see bot.js): their frames go to the
// fleet to write to the mod, and the fleet's orders for them come back here. Their chunk columns
// are shared with every other thread (see sharedChunks.js), and so are the paths they find (see
// communalPaths.js).
const { parentPort, workerData } = require('worker_threads')
const startBot = require('./bot')
const { sharedChunks } = require('./sharedChunks')
const { pathBook } = require('./communalPaths')

const chunks = sharedChunks(workerData.chunkPort, new Int32Array(workerData.signal))
const paths = pathBook()
const bots = new Map()

// The frames of one turn of the event loop, every bot's in the order sent, go to the fleet as one
// buffer handed over (transferred, never copied): one message per turn instead of one per frame.
// A small frame posted alone would also copy the whole 8 KB pool slab Node cut it from.
let frames = []
let size = 0

function send (frame) {
  if (frames.length === 0) setImmediate(flush)
  frames.push(frame)
  size += frame.length
}

function flush () {
  if (frames.length === 0) return
  const buffer = new ArrayBuffer(size)
  const bytes = new Uint8Array(buffer)
  let offset = 0
  for (const frame of frames) {
    bytes.set(frame, offset)
    offset += frame.length
  }
  frames = []
  size = 0
  parentPort.postMessage({ frames: buffer }, [buffer])
}

// Anything else for the fleet goes after the frames sent before it.
function report (message) {
  flush()
  parentPort.postMessage(message)
}

parentPort.on('message', message => {
  if (message.type === 'spawn') {
    const { name, host, port } = message
    if (bots.has(name)) throw new Error(`a bot named ${name} is already running on this thread`)
    bots.set(name, startBot({
      name,
      host,
      port,
      chunks,
      paths,
      send,
      report: m => report({ bot: name, ...m }),
      onEnd: () => {
        bots.delete(name)
        report({ bot: name, end: true })
      }
    }))
    return
  }

  // A path a bot of another thread found.
  if (message.type === 'path') {
    paths.add(message.world, message.nodes)
    return
  }

  // Already gone: the fleet learns it from the end on its way.
  const bot = bots.get(message.bot)
  if (!bot) return
  switch (message.type) {
    case 'quit':
      bot.quit()
      break
    case 'sendChunk':
      bot.sendChunk(message.key, message.claim)
      break
    case 'candidates':
      report({ bot: message.bot, formation: message.formation, candidates: bot.formationCandidates(message.target) })
      break
    case 'goto':
      bot.goto(message.spot)
      break
    default:
      throw new Error(`unknown order ${message.type} for the pool thread`)
  }
})

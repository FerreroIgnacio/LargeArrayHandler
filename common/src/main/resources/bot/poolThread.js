// Worker thread of the fleet's pool, running some of its bots (see bot.js): their frames go to the
// fleet to write to the mod, and the fleet's orders for them come back here. Their chunk columns
// are shared with every other thread (see sharedChunks.js).
//
// Everything for the fleet (frames, reports, the columns taken and let go) goes over one port, the
// chunk port, so the fleet gets it all in the order it happened here.
const { parentPort, workerData } = require('worker_threads')
const startBot = require('./bot')
const { sharedChunks, openColumns } = require('./sharedChunks')
const { threadProfiler } = require('./profiler')

openColumns(workerData.columnsFile)
const port = workerData.chunkPort
const chunks = sharedChunks(port, post, workerData.serializerPorts)
const bots = new Map()
const scope = `node.thread:pool:${workerData.index}`
const prof = threadProfiler(scope)

// The time a game tick leaves to the pathfinders of this thread, ms: under pathfinder's own 40 for
// a lone bot, so the 50 ms tick keeps room for the bots' physics and packets. The bots searching
// share it, so each gets its part (at least 1 ms) and a search is cut into as many partial paths as
// it takes; they take turns, which costs more the more of them there are. A thread with none
// searching spends none of it. Set again each time a bot starts or stops searching, comes or goes.
const TICK_BUDGET_MS = 30
// The names of the thread's bots whose pathfinder is searching.
const searching = new Set()

function shareTickTimeout () {
  const each = Math.max(1, Math.floor(TICK_BUDGET_MS / Math.max(1, searching.size)))
  for (const bot of bots.values()) bot.setTickTimeout(each)
}

// This thread's rows for a profile, and its bots': each bot's share of the heap is the thread's
// split evenly, the bots of a thread sharing one heap with nothing to tell their parts apart.
function profileRows () {
  const rows = prof.rows([[scope, 'bots', '#', bots.size]])
  const share = bots.size === 0 ? 0 : process.memoryUsage().heapUsed / bots.size
  for (const [name, bot] of bots) {
    rows.push(...bot.profile(share), [`node.bot:${name}`, 'thread', '#', workerData.index])
  }
  return rows
}

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
  port.postMessage({ frames: buffer }, [buffer])
}

// Anything else for the fleet goes after the frames sent before it.
function post (message) {
  flush()
  port.postMessage(message)
}

const report = post

parentPort.on('message', message => {
  if (message.type === 'profile') {
    post({ profile: message.profile, rows: profileRows() })
    return
  }
  if (message.type === 'spawn') {
    const { name, host, port } = message
    if (bots.has(name)) throw new Error(`a bot named ${name} is already running on this thread`)
    bots.set(name, startBot({
      name,
      host,
      port,
      chunks,
      send,
      report: m => report({ bot: name, ...m }),
      onSearching: now => {
        if (now) searching.add(name)
        else searching.delete(name)
        shareTickTimeout()
      },
      onEnd: () => {
        bots.delete(name)
        searching.delete(name)
        if (bots.size > 0) shareTickTimeout()
        report({ bot: name, end: true })
      }
    }))
    shareTickTimeout()
    return
  }

  // Already gone: the fleet learns it from the end on its way.
  const bot = bots.get(message.bot)
  if (!bot) return
  switch (message.type) {
    case 'quit':
      bot.quit()
      break
    case 'goto':
      bot.goto(message.spot)
      break
    default:
      throw new Error(`unknown order ${message.type} for the pool thread`)
  }
})

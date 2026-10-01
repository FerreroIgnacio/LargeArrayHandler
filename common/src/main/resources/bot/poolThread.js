// Worker thread of the fleet's pool, running some of its bots (see bot.js): their frames go to the
// fleet to write to the mod, and the fleet's orders for them come back here. Their chunk columns
// are shared with every other thread (see sharedChunks.js).
const { parentPort, workerData } = require('worker_threads')
const startBot = require('./bot')
const { sharedChunks } = require('./sharedChunks')

const chunks = sharedChunks(workerData.chunkPort, new Int32Array(workerData.signal))
const bots = new Map()

parentPort.on('message', message => {
  if (message.type === 'spawn') {
    const { name, host, port } = message
    if (bots.has(name)) throw new Error(`a bot named ${name} is already running on this thread`)
    bots.set(name, startBot({
      name,
      host,
      port,
      chunks,
      send: frame => parentPort.postMessage({ frame }),
      onEnd: () => {
        bots.delete(name)
        parentPort.postMessage({ bot: name, end: true })
      }
    }))
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
    case 'formation':
      bot.whenSpawned(() => parentPort.postMessage({ bot: message.bot, candidates: bot.formationCandidates(message.target) }))
      break
    case 'goto':
      bot.goto(message.spot)
      break
    default:
      throw new Error(`unknown order ${message.type} for the pool thread`)
  }
})

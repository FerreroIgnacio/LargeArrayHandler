// A path thread of the fleet: searches the bots' paths (see communalPaths.js), never on a bot's
// thread, in a world made of every column any bot of the fleet holds (the slots of columns.bin, see
// sharedChunks.js, handed over by the fleet): a bot gets a path through columns the server never
// sent it. Pool threads ask over a port of their own (see pathClient.js). A step of one search per turn of
// the event loop, the searches taking turns, so columns, cancels and new requests are taken in between.
const { parentPort, workerData } = require('worker_threads')
const { Vec3 } = require('vec3')
const nbt = require('prismarine-nbt')
const Move = require('mineflayer-pathfinder/lib/move')
const Movements = require('mineflayer-pathfinder/lib/movements')
const { GoalBlock } = require('mineflayer-pathfinder/lib/goals')
const { columnClass, openColumns } = require('./sharedChunks')
const { pathBook, plan } = require('./communalPaths')
const { threadProfiler } = require('./profiler')

// Searches past this many nodes go to the log, with what they cost and how many requests wait.
const LOG_SEARCH_NODES = 1000

openColumns(workerData.columnsFile)
const scope = `node.thread:path:${workerData.index}`
const prof = threadProfiler(scope)
// Bot -> what its searches on this thread took: { ms, searches, nodes }.
const searched = new Map()
// The fleet's paths: those this thread finds or cuts go to the other path threads through the fleet.
const book = pathBook(workerData.index, (world, id, nodes) => parentPort.postMessage({ path: { world, id, nodes } }))
// "server|dimension" -> "cx,cz" -> column: the fleet's columns.
const worlds = new Map()
// Minecraft version -> { Column, movements, bot }: the stand-in bot pathfinder's Movements reads.
const versions = new Map()

function forVersion (version) {
  let entry = versions.get(version)
  if (entry) return entry
  const registry = require('prismarine-registry')(version)
  const bot = {
    registry,
    game: { minY: 0 },
    entity: { effects: {} },
    entities: {},
    items: [],
    columns: null,
    inventory: { items: () => bot.items },
    blockAt: pos => blockAt(bot.columns, pos),
    // pathfinder's own: the fastest tool of the inventory to dig the block.
    pathfinder: {
      bestHarvestTool (block) {
        let fastest = Number.MAX_VALUE
        let best = null
        for (const tool of bot.items) {
          const enchants = tool.nbt ? nbt.simplify(tool.nbt).Enchantments : []
          const digTime = block.digTime(tool.type, false, false, false, enchants, bot.entity.effects)
          if (digTime < fastest) {
            fastest = digTime
            best = tool
          }
        }
        return best
      }
    }
  }
  entry = { Column: columnClass(registry), movements: new Movements(bot), bot }
  versions.set(version, entry)
  return entry
}

// The column last looked up: a search reads mostly the same few. Forgotten whenever the fleet's
// columns change (those come between searches, never during one).
const last = { columns: null, cx: 0, cz: 0, column: null }
const local = new Vec3(0, 0, 0)
// The "cx,cz" of the columns the search being run looked for and the fleet does not have in memory
// (see run).
let missing = new Set()

// A column still being loaded by its first bot counts as not there.
function blockAt (columns, pos) {
  const x = Math.floor(pos.x)
  const y = Math.floor(pos.y)
  const z = Math.floor(pos.z)
  const cx = x >> 4
  const cz = z >> 4
  if (last.columns !== columns || last.cx !== cx || last.cz !== cz) {
    last.columns = columns
    last.cx = cx
    last.cz = cz
    const id = `${cx},${cz}`
    last.column = columns?.get(id)
    if (!last.column) missing.add(id)
  }
  const column = last.column
  if (!column || !column.isReady()) return null
  local.set(x & 15, y, z & 15)
  const block = column.getBlock(local)
  block.position = new Vec3(x, y, z)
  return block
}

// "server|dimension|cx,cz" -> its world and its "cx,cz".
function split (key) {
  const bar = key.lastIndexOf('|')
  return { world: key.slice(0, bar), id: key.slice(bar + 1) }
}

function goalOf (goal) {
  if (goal.kind === 'block') return new GoalBlock(goal.x, goal.y, goal.z)
  throw new Error(`unknown goal ${goal.kind} for the path thread`)
}

// The moves as they go over the port.
function wire (moves) {
  return moves.map(m => ({ x: m.x, y: m.y, z: m.z, remainingBlocks: m.remainingBlocks, cost: m.cost, toBreak: m.toBreak, toPlace: m.toPlace, parkour: m.parkour }))
}

// What the search of a request sees: the world of the fleet, the bot's items, effects and entities.
// Set before every step, the searches taking turns on the same movements.
function prepare (request) {
  const { bot, movements } = forVersion(request.version)
  bot.columns = worlds.get(request.world) ?? new Map()
  bot.items = request.items
  bot.entity.effects = request.effects
  bot.entities = request.entities
  movements.clearCollisionIndex()
  movements.updateCollisionIndex()
  return { bot, movements }
}

// The steps of a request's search (see communalPaths.js's plan): yields null or { partial } as plan
// does, returns the answer once the search ends.
function * search (request) {
  const { bot, movements } = forVersion(request.version)
  const goal = goalOf(request.goal)
  if (!bot.blockAt(goal)) {
    return { id: request.id, status: 'noPath', reason: 'target not loaded', visitedNodes: 0, generatedNodes: 0, moves: [] }
  }
  const { start } = request
  const found = yield * plan(book, request.world, new Move(start.x, start.y, start.z, start.remainingBlocks, 0), movements, goal)
  return {
    id: request.id,
    status: found.status,
    visitedNodes: found.visitedNodes,
    generatedNodes: found.generatedNodes,
    moves: wire(found.path)
  }
}

// The searches taking turns: { port, request, asked, steps, ms, nodes }. Each turn the first one
// takes a step and goes to the back, so a search never holds the thread and none waits for another to end.
// asked: the "cx,cz" this request asked the fleet for already.
const searches = []
// Searches put aside until the fleet has the snapshots they wanted: ticket -> the same entries.
// A search that looked for columns the fleet does not have in memory, and the request never asked
// for, is put aside while the fleet reads them off their snapshots (see fleet.js), then begun again
// once the fleet says they are in (or have none). Columns with no snapshot stay unknown: the search
// goes on without them.
const parked = new Map()
let nextTicket = 1
// Tickets of searches cancelled while put aside: the fleet's answer to them goes nowhere.
const cancelledTickets = new Set()

function turn () {
  const job = searches.shift()
  step(job)
  if (searches.length > 0) setImmediate(turn)
}

function enqueue (job) {
  if (searches.length === 0) setImmediate(turn)
  searches.push(job)
}

function step (job) {
  const { port, request } = job
  prepare(request)
  missing = new Set()
  // The column last looked up may be one missing: looked up again, to be counted.
  last.columns = null
  const began = performance.now()
  const { done, value } = job.steps.next()
  job.ms += performance.now() - began
  const wanted = [...missing].filter(id => !job.asked.has(id))
  if (wanted.length > 0) {
    for (const id of wanted) job.asked.add(id)
    const ticket = nextTicket++
    parked.set(ticket, job)
    parentPort.postMessage({ wanted: { world: request.world, ids: wanted, ticket } })
    return
  }
  if (done) {
    let spent = searched.get(request.bot)
    if (!spent) searched.set(request.bot, (spent = { ms: 0, searches: 0, nodes: 0 }))
    spent.ms += job.ms
    spent.searches++
    spent.nodes += value.visitedNodes
    if (value.visitedNodes > LOG_SEARCH_NODES) {
      console.log(`[path thread ${workerData.index}] ${value.visitedNodes} nodes in ${Math.round(job.ms)} ms (${value.status}), ${searches.length} taking turns`)
    }
    port.postMessage(value)
    return
  }
  // Nearer to the goal than ever: the bot walks the path to there while the search goes on.
  if (value) port.postMessage({ id: request.id, status: 'partial', moves: wire(value.partial) })
  searches.push(job)
}

function onRequest (port, message) {
  if (message.cancel !== undefined) {
    // Taking turns, or put aside: dropped, and said so. Done: its answer is on the way.
    const i = searches.findIndex(q => q.port === port && q.request.id === message.cancel)
    if (i >= 0) {
      searches.splice(i, 1)
      port.postMessage({ id: message.cancel, cancelled: true })
      return
    }
    for (const [ticket, p] of parked) {
      if (p.port !== port || p.request.id !== message.cancel) continue
      parked.delete(ticket)
      cancelledTickets.add(ticket)
      port.postMessage({ id: message.cancel, cancelled: true })
      return
    }
    return
  }
  enqueue({ port, request: message, asked: new Set(), steps: search(message), ms: 0 })
}

parentPort.on('message', message => {
  if (message.type === 'column' || message.type === 'drop') last.columns = null
  switch (message.type) {
    case 'client':
      message.port.on('message', m => onRequest(message.port, m))
      break
    case 'column': {
      const { world, id } = split(message.key)
      let columns = worlds.get(world)
      if (!columns) worlds.set(world, (columns = new Map()))
      columns.set(id, new (forVersion(message.version).Column)(message.header, message.slot))
      break
    }
    case 'drop': {
      const { world, id } = split(message.key)
      if (!worlds.get(world)?.delete(id)) throw new Error(`the fleet dropped column ${message.key} this path thread never had`)
      // Taken between searches: none reads the slot any more, the fleet may load another column into it.
      parentPort.postMessage({ dropped: message.slot })
      break
    }
    case 'path':
      // Found or cut by another path thread.
      book.apply(message.world, message.id, message.nodes)
      break
    case 'profile': {
      const rows = prof.rows([[scope, 'queue', '#', searches.length], [scope, 'parked', '#', parked.size]])
      for (const [bot, spent] of searched) {
        rows.push([`node.bot:${bot}`, 'cpu.path', 'ms', spent.ms], [`node.bot:${bot}`, 'path.searches', 'n', spent.searches], [`node.bot:${bot}`, 'path.nodes', 'n', spent.nodes])
      }
      parentPort.postMessage({ profile: message.profile, rows })
      break
    }
    case 'wanted': {
      // The columns the ticket wanted are in (their 'column' came first), or have no snapshot: searched again.
      if (cancelledTickets.delete(message.ticket)) break
      const job = parked.get(message.ticket)
      if (!job) throw new Error(`the fleet answered ticket ${message.ticket}, which no request waits on`)
      parked.delete(message.ticket)
      // Begun again: what it read before was without those columns.
      job.steps = search(job.request)
      enqueue(job)
      break
    }
    default:
      throw new Error(`unknown message ${message.type} for the path thread`)
  }
})

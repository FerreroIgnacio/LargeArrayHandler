// A path thread of the fleet: searches the bots' paths (see communalPaths.js), never on a bot's
// thread, in a world made of every column any bot of the fleet holds (the slots of columns.bin, see
// sharedChunks.js, handed over by the fleet): a bot gets a path through columns the server never
// sent it. Pool threads ask over a port of their own (see pathClient.js). One search per turn of
// the event loop, so columns, cancels and new requests are taken in between.
const { parentPort, workerData } = require('worker_threads')
const { Vec3 } = require('vec3')
const nbt = require('prismarine-nbt')
const Move = require('mineflayer-pathfinder/lib/move')
const Movements = require('mineflayer-pathfinder/lib/movements')
const { GoalBlock, GoalNearXZ } = require('mineflayer-pathfinder/lib/goals')
const { columnClass, openColumns } = require('./sharedChunks')
const { pathBook, plan, MAX_EXPANDED } = require('./communalPaths')
const { threadProfiler } = require('./profiler')

// Searches past this many nodes go to the log, with what they cost and how many requests wait.
const LOG_SEARCH_NODES = 1000

openColumns(workerData.columnsFile)
const scope = `node.thread:path:${workerData.index}`
const prof = threadProfiler(scope)
// Bot -> what its searches on this thread took: { ms, searches, nodes }.
const searched = new Map()
const book = pathBook()
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
  if (goal.kind === 'nearXZ') return new GoalNearXZ(goal.x, goal.z, goal.range)
  throw new Error(`unknown goal ${goal.kind} for the path thread`)
}

// Searches the request, waiting on snapshots: a search that looked for columns the fleet does not
// have in memory, and the request never asked for, is put aside while the fleet reads them off
// their snapshots (see fleet.js), then run again once the fleet says they are in (or have none).
// Columns with no snapshot stay unknown: the search answers with the best path it finds without them.
// asked: the "cx,cz" this request asked the fleet for already.
function run (port, request, asked) {
  missing = new Set()
  // The column last looked up may be one missing: looked up again, to be counted.
  last.columns = null
  const result = search(request)
  const wanted = [...missing].filter(id => !asked.has(id))
  if (wanted.length === 0) {
    port.postMessage(result)
    return
  }
  for (const id of wanted) asked.add(id)
  const ticket = nextTicket++
  parked.set(ticket, { port, request, asked })
  parentPort.postMessage({ wanted: { world: request.world, ids: wanted, ticket } })
}

function search (request) {
  const { bot, movements } = forVersion(request.version)
  bot.columns = worlds.get(request.world) ?? new Map()
  bot.items = request.items
  bot.entity.effects = request.effects
  bot.entities = request.entities
  const goal = goalOf(request.goal)
  if (request.goal.kind === 'block' && !bot.blockAt(goal)) {
    return { id: request.id, status: 'noPath', reason: 'target not loaded', visitedNodes: 0, generatedNodes: 0, moves: [] }
  }
  movements.clearCollisionIndex()
  movements.updateCollisionIndex()
  const { start } = request
  const began = performance.now()
  const found = plan(book, request.world, new Move(start.x, start.y, start.z, start.remainingBlocks, 0), movements, goal)
  const took = performance.now() - began
  let spent = searched.get(request.bot)
  if (!spent) searched.set(request.bot, (spent = { ms: 0, searches: 0, nodes: 0 }))
  spent.ms += took
  spent.searches++
  spent.nodes += found.visitedNodes
  if (found.visitedNodes > LOG_SEARCH_NODES) {
    console.log(`[path thread ${workerData.index}] ${found.visitedNodes} nodes in ${Math.round(took)} ms (${found.status}), ${queue.length} waiting`)
  }
  // The other path threads join it too.
  if (found.nodes) parentPort.postMessage({ path: { world: request.world, nodes: found.nodes } })
  return {
    id: request.id,
    status: found.status,
    reason: found.status !== 'success' && found.exhausted ? `gave up after ${MAX_EXPANDED} nodes` : undefined,
    visitedNodes: found.visitedNodes,
    generatedNodes: found.generatedNodes,
    moves: found.path.map(m => ({ x: m.x, y: m.y, z: m.z, remainingBlocks: m.remainingBlocks, cost: m.cost, toBreak: m.toBreak, toPlace: m.toPlace, parkour: m.parkour }))
  }
}

// Requests waiting, in order: { port, request, asked }.
const queue = []
// Requests put aside until the fleet has the snapshots they wanted: ticket -> { port, request, asked }.
const parked = new Map()
let nextTicket = 1
// Tickets of requests cancelled while put aside: the fleet's answer to them goes nowhere.
const cancelledTickets = new Set()

function turn () {
  const { port, request, asked } = queue.shift()
  run(port, request, asked)
  if (queue.length > 0) setImmediate(turn)
}

function enqueue (entry) {
  if (queue.length === 0) setImmediate(turn)
  queue.push(entry)
}

function onRequest (port, message) {
  if (message.cancel !== undefined) {
    // Not started yet, or put aside: dropped, and said so. Being searched or done: its answer is on the way.
    const i = queue.findIndex(q => q.port === port && q.request.id === message.cancel)
    if (i >= 0) {
      queue.splice(i, 1)
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
  enqueue({ port, request: message, asked: new Set() })
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
      book.add(message.world, message.nodes)
      break
    case 'profile': {
      const rows = prof.rows([[scope, 'queue', '#', queue.length], [scope, 'parked', '#', parked.size]])
      for (const [bot, spent] of searched) {
        rows.push([`node.bot:${bot}`, 'cpu.path', 'ms', spent.ms], [`node.bot:${bot}`, 'path.searches', 'n', spent.searches], [`node.bot:${bot}`, 'path.nodes', 'n', spent.nodes])
      }
      parentPort.postMessage({ profile: message.profile, rows })
      break
    }
    case 'wanted': {
      // The columns the ticket wanted are in (their 'column' came first), or have no snapshot: searched again.
      if (cancelledTickets.delete(message.ticket)) break
      const entry = parked.get(message.ticket)
      if (!entry) throw new Error(`the fleet answered ticket ${message.ticket}, which no request waits on`)
      parked.delete(message.ticket)
      enqueue(entry)
      break
    }
    default:
      throw new Error(`unknown message ${message.type} for the path thread`)
  }
})

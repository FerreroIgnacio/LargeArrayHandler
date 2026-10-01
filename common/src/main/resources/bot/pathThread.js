// A path thread of the fleet: searches the bots' paths (see communalPaths.js), never on a bot's
// thread, in a world made of every column any bot of the fleet holds (the shared buffers of
// sharedChunks.js, handed over by the fleet): a bot gets a path through columns the server never
// sent it. Pool threads ask over a port of their own (see pathClient.js). One search per turn of
// the event loop, so columns, cancels and new requests are taken in between.
const { parentPort } = require('worker_threads')
const { Vec3 } = require('vec3')
const nbt = require('prismarine-nbt')
const Move = require('mineflayer-pathfinder/lib/move')
const Movements = require('mineflayer-pathfinder/lib/movements')
const { GoalBlock, GoalNearXZ } = require('mineflayer-pathfinder/lib/goals')
const { columnClass } = require('./sharedChunks')
const { pathBook, plan, MAX_EXPANDED } = require('./communalPaths')

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

// A column still being loaded by its first bot counts as not there.
function blockAt (columns, pos) {
  const x = Math.floor(pos.x)
  const y = Math.floor(pos.y)
  const z = Math.floor(pos.z)
  const column = columns?.get(`${x >> 4},${z >> 4}`)
  if (!column || !column.isReady()) return null
  const block = column.getBlock(new Vec3(x & 15, y, z & 15))
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

function answer (request) {
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
  const found = plan(book, request.world, new Move(start.x, start.y, start.z, start.remainingBlocks, 0), movements, goal)
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

// Requests waiting, in order: { port, request }.
const queue = []

function turn () {
  const { port, request } = queue.shift()
  port.postMessage(answer(request))
  if (queue.length > 0) setImmediate(turn)
}

function onRequest (port, message) {
  if (message.cancel !== undefined) {
    // Not started yet: dropped, and said so. Started or done: its answer is on the way.
    const i = queue.findIndex(q => q.port === port && q.request.id === message.cancel)
    if (i >= 0) {
      queue.splice(i, 1)
      port.postMessage({ id: message.cancel, cancelled: true })
    }
    return
  }
  if (queue.length === 0) setImmediate(turn)
  queue.push({ port, request: message })
}

parentPort.on('message', message => {
  switch (message.type) {
    case 'client':
      message.port.on('message', m => onRequest(message.port, m))
      break
    case 'column': {
      const { world, id } = split(message.key)
      let columns = worlds.get(world)
      if (!columns) worlds.set(world, (columns = new Map()))
      columns.set(id, new (forVersion(message.version).Column)(message.buffer))
      break
    }
    case 'drop': {
      const { world, id } = split(message.key)
      if (!worlds.get(world)?.delete(id)) throw new Error(`the fleet dropped column ${message.key} this path thread never had`)
      break
    }
    case 'path':
      book.add(message.world, message.nodes)
      break
    default:
      throw new Error(`unknown message ${message.type} for the path thread`)
  }
})

// One mineflayer bot inside the fleet, and its chunks reported to the mod's registry: a claim per
// column loaded, the column serialized when the registry asks for it, then its block and block
// entity changes, and the unload.
const mineflayer = require('mineflayer')
const nbt = require('prismarine-nbt')
const { Vec3 } = require('vec3')
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder')
const protocol = require('./protocol')
const states = require('./states')
const serialize = require('./serializer')
const { communalPlanner } = require('./communalPaths')

// A spot the bot does not have loaded is walked to in hops of this many blocks toward it, each
// inside what the bot sees (the server sends at least two chunks around it): the next hop once
// it gets there, the spot itself once its block arrives.
const HOP = 24
// How close to a hop's end (blocks) counts as there.
const HOP_REACH = 2
// Searches past this many nodes go to the log, with how long they took to be done (taking turns with
// the other searches of the thread included).
const LOG_SEARCH_NODES = 1000

// send(frame) writes to the mod, report(message) tells the fleet, onEnd() once the bot is gone and
// its last frame sent; chunks and paths hold the columns and the paths of its thread (see
// sharedChunks.js, communalPaths.js).
module.exports = function startBot ({ name, host, port, chunks, paths, send, report, onEnd }) {
  const server = `${host}:${port}`
  const bot = mineflayer.createBot({ username: name, host, port, auth: 'offline' })
  chunks.attach(bot, name, server)
  const log = line => console.log(`[${name}] ${line}`)

  // "cx,cz" -> { key, claim }: the columns claimed and not unloaded, with the dimension they were claimed in.
  const columns = new Map()
  let nextClaim = 1

  const out = send

  // Block updates of one event loop turn, per column, sent as one message (a multi block change is
  // many updates in one packet). Flushed before anything else goes out, to keep the order.
  const batches = new Map()
  let flushQueued = false
  function flush () {
    for (const { key, changes } of batches.values()) out(protocol.blockUpdate(name, key, changes))
    batches.clear()
  }

  let tools
  const blockStates = () => (tools ??= states(bot.registry))

  function dimension () {
    const d = bot.game.dimension
    return d.includes(':') ? d : `minecraft:${d}`
  }

  // Where the bot is: "server|dimension".
  const world = () => `${server}|${dimension()}`

  function held (x, z) {
    const column = columns.get(`${x >> 4},${z >> 4}`)
    if (!column) throw new Error(`${name}: change at ${x},${z} in a column it never claimed`)
    return column
  }

  bot.loadPlugin(pathfinder)
  bot.once('spawn', () => {
    bot.pathfinder.setMovements(new Movements(bot))
    // The fleet passes each path found on to the other threads, and starts a formation's other
    // bots once its first one has its path (see fleet.js).
    bot.pathfinder.getPathTo = communalPlanner(bot, paths, world, (goal, nodes) => {
      report({ planned: goal })
      if (nodes) report({ path: { world: world(), nodes } })
    })
    log('spawned')
    out(protocol.botSpawned(name))
  })
  // Every spawn, the first and each respawn or change of dimension: the fleet keeps where each bot is.
  bot.on('spawn', () => report({ world: world() }))
  // The mod draws the path being walked and its target; an empty PATH clears it.
  const goalOf = () => {
    const g = bot.pathfinder.goal
    // A hop has no y: drawn at the bot's.
    return g && g.x !== undefined ? { x: g.x, y: g.y ?? Math.floor(bot.entity.position.y), z: g.z } : null
  }
  bot.on('path_update', result => {
    if (result.status === 'noPath') log('formation: no path' + (result.reason ? ` (${result.reason})` : ''))
    if (result.visitedNodes > LOG_SEARCH_NODES) log(`search: ${result.visitedNodes} nodes, done after ${Math.round(result.time)} ms (${result.status})`)
    const target = goalOf()
    if (result.status === 'noPath' || !target) out(protocol.path(name, null))
    else out(protocol.path(name, target, result.path))
  })
  bot.on('goal_reached', goal => {
    out(protocol.path(name, null))
    // pathfinder clears its goal right after this event: the next one goes once it is done.
    if (hopping) queueMicrotask(walk)
    // Already there: no path to find, for the formation waiting on it either.
    report({ planned: goal && goal.x !== undefined ? { x: goal.x, y: goal.y, z: goal.z } : null })
  })
  bot.on('path_stop', () => out(protocol.path(name, null)))
  bot.on('path_reset', () => out(protocol.path(name, null)))

  // Feet and head free, something solid below.
  function standable (p) {
    const feet = bot.blockAt(p)
    const head = bot.blockAt(p.offset(0, 1, 0))
    const floor = bot.blockAt(p.offset(0, -1, 0))
    if (!feet || !head || !floor) return false
    return feet.boundingBox === 'empty' && head.boundingBox === 'empty' && floor.boundingBox === 'block'
  }
  bot.on('kicked', reason => log('kicked: ' + reason))
  bot.on('error', err => log('error: ' + err.message))

  bot.on('chunkColumnLoad', point => {
    // The column of the spot being hopped to: straight there now.
    if (hopping && point.x >> 4 === formationSpot.x >> 4 && point.z >> 4 === formationSpot.z >> 4) walk()
    flush()
    const id = `${point.x >> 4},${point.z >> 4}`
    // The server sent the column again: drop the old claim and start over.
    if (columns.has(id)) out(protocol.unload(name, columns.get(id).key))
    const column = { key: { server, dimension: dimension(), x: point.x >> 4, z: point.z >> 4 }, claim: nextClaim++ }
    columns.set(id, column)
    out(protocol.claim(name, column.key, column.claim))
  })

  bot.on('chunkColumnUnload', point => {
    flush()
    const id = `${point.x >> 4},${point.z >> 4}`
    const column = columns.get(id)
    if (!column) throw new Error(`${name}: unloaded column ${id} it never claimed`)
    columns.delete(id)
    out(protocol.unload(name, column.key))
  })

  bot.on('blockUpdate', (oldBlock, newBlock) => {
    const p = newBlock.position
    const column = held(p.x, p.z)
    const { stateName, stateIdOf } = blockStates()
    // The column is shared: the first bot of the fleet to get the change already wrote it there,
    // and reported it. The rest see no change, and the registry hears it once instead of once per bot.
    if (stateIdOf(oldBlock) === stateIdOf(newBlock)) return
    const id = `${column.key.x},${column.key.z}`
    let batch = batches.get(id)
    if (!batch) batches.set(id, (batch = { key: column.key, changes: [] }))
    batch.changes.push({ x: p.x, y: p.y, z: p.z, state: stateName(stateIdOf(newBlock)) })
    if (!flushQueued) {
      flushQueued = true
      queueMicrotask(() => {
        flushQueued = false
        flush()
      })
    }
  })

  bot._client.on('tile_entity_data', packet => {
    if (!packet.location) throw new Error(`${name}: tile_entity_data without a location`)
    const { x, y, z } = packet.location
    // mineflayer drops it too: no column there, nothing holds it.
    if (!bot.world.getColumn(x >> 4, z >> 4)) return
    flush()
    const column = held(x, z)
    const tag = packet.nbtData
    if (!tag) {
      out(protocol.blockEntityUpdate(name, column.key, packet.location, null))
      return
    }
    const { stateName, stateIdOf } = blockStates()
    out(protocol.blockEntityUpdate(name, column.key, packet.location, {
      type: states.blockName(stateName(stateIdOf(bot.blockAt(new Vec3(x, y, z))))),
      nbt: nbt.writeUncompressed({ ...tag, name: tag.name ?? '' })
    }))
  })

  bot.on('end', reason => {
    // mineflayer's dig timer outlives the connection: it would set the block to air in a world, and
    // shared columns, this bot no longer holds.
    bot.stopDigging()
    log('disconnected: ' + reason)
    flush()
    columns.clear()
    out(protocol.botGone(name))
    onEnd()
  })

  // Where this bot was last sent to stand in a formation: {x, y, z}, null until it is.
  let formationSpot = null
  // Walking hops toward it, its block not loaded yet.
  let hopping = false

  // To the spot if its block is loaded, else a hop toward it.
  function walk () {
    const spot = formationSpot
    hopping = !bot.blockAt(new Vec3(spot.x, spot.y, spot.z))
    if (!hopping) {
      bot.pathfinder.setGoal(new goals.GoalBlock(spot.x, spot.y, spot.z))
      return
    }
    const p = bot.entity.position
    const dx = spot.x + 0.5 - p.x
    const dz = spot.z + 0.5 - p.z
    const k = HOP / Math.hypot(dx, dz)
    bot.pathfinder.setGoal(new goals.GoalNearXZ(p.x + dx * k, p.z + dz * k, HOP_REACH))
  }

  return {
    quit: () => bot.quit(),

    spot: () => formationSpot,

    // The block and the standable ones around it, nearest first (breadth first, one step sideways
    // and up to one up or down), each with its key: "server|dimension|x,y,z". Asked of one bot per
    // formation; the fleet hands the spots out to all of its bots (see fleet.js).
    formationCandidates ({ x, y, z }) {
      const target = new Vec3(x, y, z)
      if (!bot.blockAt(target)) throw new Error(`${name}: formation target ${x},${y},${z} is not loaded`)
      const candidates = []
      const seen = new Set([target.toString()])
      const queue = [target]
      for (let i = 0; i < queue.length && i < 4096; i++) {
        const p = queue[i]
        if (standable(p)) candidates.push({ spot: { x: p.x, y: p.y, z: p.z }, key: `${world()}|${p.x},${p.y},${p.z}` })
        for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
          for (const dy of [0, 1, -1]) {
            const n = p.offset(dx, dy, dz)
            if (seen.has(n.toString()) || !bot.blockAt(n)) continue
            seen.add(n.toString())
            queue.push(n)
          }
        }
      }
      if (candidates.length === 0) throw new Error(`${name}: no block to stand on around ${x},${y},${z}`)
      return candidates
    },

    // Walks to the spot taken.
    goto (spot) {
      formationSpot = spot
      walk()
    },

    // The registry wants the blocks of the column claimed as `claim`; a claim since unloaded is
    // already reported, and the registry asks the next holder instead.
    sendChunk (key, claim) {
      const column = columns.get(`${key.x},${key.z}`)
      if (!column || column.claim !== claim) return
      flush()
      const raw = bot.world.getColumn(key.x, key.z)
      if (!raw) throw new Error(`${name}: claimed column ${key.x},${key.z} is not loaded`)
      out(protocol.chunkData(name, column.key, bot.version, serialize(raw, blockStates().stateName)))
    }
  }
}

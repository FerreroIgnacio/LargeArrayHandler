// One mineflayer bot inside the fleet, and the block entities it sees reported to the mod's
// registry. The columns themselves are claimed, readied and unloaded once for the whole fleet, as
// they come and go from its shared index, their blocks read by the mod straight off their slot
// (see sharedChunks.js, fleet.js).
const mineflayer = require('mineflayer')
const nbt = require('prismarine-nbt')
const { Vec3 } = require('vec3')
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder')
const protocol = require('./protocol')
const states = require('./states')
const { botMeter } = require('./profiler')

// Searches past this many nodes go to the log, with how long they took to be done (taking turns with
// the other searches of the thread included).
const LOG_SEARCH_NODES = 1000

// send(frame) writes to the mod, report(message) tells the fleet, onEnd() once the bot is gone and
// its last frame sent; chunks holds the columns of its thread (see sharedChunks.js).
module.exports = function startBot ({ name, host, port, chunks, send, report, onEnd }) {
  const server = `${host}:${port}`
  // Always the fewest chunks it can ask for; a 1.12.2 server (a LAN world) sends its own view distance whatever it asks.
  const bot = mineflayer.createBot({ username: name, host, port, viewDistance: 2, auth: 'offline' })
  const log = line => console.log(`[${name}] ${line}`)
  // The time its handlers take on the thread: mineflayer's events and the client's packets.
  const meter = botMeter()
  meter.wrap(bot)
  meter.wrap(bot._client)

  function dimension () {
    const d = bot.game.dimension
    return d.includes(':') ? d : `minecraft:${d}`
  }

  // Where the bot is: "server|dimension", as the shared columns are keyed.
  const world = () => `${server}|${dimension()}`

  // "cx,cz" -> the registry's key of the column: the columns loaded, with the dimension they were loaded in.
  const columns = new Map()

  const out = send

  let tools
  const blockStates = () => (tools ??= states(bot.registry))

  function held (x, z) {
    const key = columns.get(`${x >> 4},${z >> 4}`)
    if (!key) throw new Error(`${name}: change at ${x},${z} in a column it never loaded`)
    return key
  }

  // Columns changed in this microtask ("server|dimension|cx,cz"): the fleet has the mod snapshot
  // each again (see fleet.js), once whatever the number of changes in them.
  const changed = new Set()
  function reportChanged () {
    for (const column of changed) report({ changed: column })
    changed.clear()
  }
  function markChanged (x, z) {
    const key = held(x, z)
    if (changed.size === 0) queueMicrotask(reportChanged)
    changed.add(`${key.server}|${key.dimension}|${key.x},${key.z}`)
  }
  // Before the column is released (sharedChunks.js, which listens first): the fleet hears of a
  // change only while some bot holds the column.
  bot.prependListener('chunkColumnUnload', reportChanged)
  bot.prependListener('end', reportChanged)

  // The block entity at location (world), tag its NBT or null once removed, to the registry.
  function reportBlockEntity (location, tag) {
    const key = held(location.x, location.z)
    if (!tag) {
      out(protocol.blockEntityUpdate(name, key, location, null))
      return
    }
    const { stateName, stateIdOf } = blockStates()
    out(protocol.blockEntityUpdate(name, key, location, {
      type: states.blockName(stateName(stateIdOf(bot.blockAt(new Vec3(location.x, location.y, location.z))))),
      nbt: nbt.writeUncompressed({ ...tag, name: tag.name ?? '' })
    }))
  }

  // Those of the map_chunk packets this bot loaded into the shared column.
  chunks.attach(bot, name, world, tag => reportBlockEntity({ x: tag.value.x.value, y: tag.value.y.value, z: tag.value.z.value }, tag))

  bot.loadPlugin(pathfinder)
  // Pathfinder's time per tick, in ms, once the pool thread has shared its own out (see poolThread.js).
  let tickTimeout = null
  bot.once('spawn', () => {
    bot.pathfinder.setMovements(new Movements(bot))
    if (tickTimeout !== null) bot.pathfinder.tickTimeout = tickTimeout
    log('spawned')
    out(protocol.botSpawned(name))
  })
  // Every spawn, the first and each respawn or change of dimension: the fleet keeps where each bot is.
  bot.on('spawn', () => report({ world: world() }))
  // The mod draws the path being walked and its target; an empty PATH clears it.
  const goalOf = () => {
    const g = bot.pathfinder.goal
    return g ? { x: g.x, y: g.y, z: g.z } : null
  }
  bot.on('path_update', result => {
    if (result.status === 'noPath') log('no path' + (result.reason ? ` (${result.reason})` : ''))
    if (result.visitedNodes > LOG_SEARCH_NODES) log(`search: ${result.visitedNodes} nodes, done after ${Math.round(result.time)} ms (${result.status})`)
    const target = goalOf()
    if (result.status === 'noPath' || !target) out(protocol.path(name, null))
    else out(protocol.path(name, target, result.path))
  })
  bot.on('goal_reached', () => out(protocol.path(name, null)))
  bot.on('path_stop', () => out(protocol.path(name, null)))
  bot.on('path_reset', () => out(protocol.path(name, null)))

  bot.on('kicked', reason => log('kicked: ' + reason))
  bot.on('error', err => log('error: ' + err.message))

  bot.on('chunkColumnLoad', point => {
    columns.set(`${point.x >> 4},${point.z >> 4}`, { server, dimension: dimension(), x: point.x >> 4, z: point.z >> 4 })
  })

  bot.on('chunkColumnUnload', point => {
    const id = `${point.x >> 4},${point.z >> 4}`
    if (!columns.delete(id)) throw new Error(`${name}: unloaded column ${id} it never loaded`)
  })

  bot._client.on('tile_entity_data', packet => {
    if (!packet.location) throw new Error(`${name}: tile_entity_data without a location`)
    const { x, z } = packet.location
    // mineflayer drops it too: no column there, nothing holds it.
    if (!bot.world.getColumn(x >> 4, z >> 4)) return
    reportBlockEntity(packet.location, packet.nbtData)
    markChanged(x, z)
  })

  bot.on('blockUpdate', (oldBlock, newBlock) => {
    // The column is shared: the first bot of the fleet to get the change already wrote it there.
    // The rest see no change, and the column is snapshotted once instead of once per bot.
    const { stateIdOf } = blockStates()
    if (stateIdOf(oldBlock) === stateIdOf(newBlock)) return
    markChanged(newBlock.position.x, newBlock.position.z)
  })

  bot.on('end', reason => {
    // mineflayer's dig timer outlives the connection: it would set the block to air in a world, and
    // shared columns, this bot no longer holds.
    // The digging plugin is only there once the bot got to be injected; one that ended before has no timer.
    if (bot.stopDigging) bot.stopDigging()
    log('disconnected: ' + reason)
    columns.clear()
    out(protocol.botGone(name))
    onEnd()
  })

  return {
    quit: () => bot.quit(),

    // Its rows for a profile (see profiler.js); `heapShare` its part of the thread's heap.
    profile (heapShare) {
      const scope = `node.bot:${name}`
      // No socket until it connects, no entities until it logs in: none of them yet.
      const socket = bot._client.socket
      return [
        [scope, 'cpu.handlers', 'ms', meter.ms],
        [scope, 'events', 'n', meter.events],
        [scope, 'net.in', 'bytes', socket ? socket.bytesRead : 0],
        [scope, 'net.out', 'bytes', socket ? socket.bytesWritten : 0],
        [scope, 'mem.heapShare', 'B', heapShare],
        [scope, 'columns', '#', columns.size],
        [scope, 'entities', '#', bot.entities ? Object.keys(bot.entities).length : 0]
      ]
    },

    // Its pathfinder's time per tick, in ms (see poolThread.js): a search that runs out of it returns what it has so far.
    // The plugin is only injected once the server's version is known: set when the bot spawns if it is not there yet.
    setTickTimeout (ms) {
      tickTimeout = ms
      if (bot.pathfinder) bot.pathfinder.tickTimeout = ms
    },

    // Walks to the spot: its own search, from where it stands.
    goto (spot) {
      bot.pathfinder.setGoal(new goals.GoalBlock(spot.x, spot.y, spot.z))
    }
  }
}

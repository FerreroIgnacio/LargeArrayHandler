// One mineflayer bot inside the fleet, and its chunks reported to the mod's registry: a claim per
// column loaded, the column serialized when the registry asks for it, then its block and block
// entity changes, and the unload.
const mineflayer = require('mineflayer')
const nbt = require('prismarine-nbt')
const { Vec3 } = require('vec3')
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder')
const protocol = require('./protocol')
const states = require('./states')

// send(frame) writes to the mod, serialize(job) resolves to a CHUNK_DATA frame, onEnd() once the bot is gone.
module.exports = function startBot ({ name, host, port, send, serialize, onEnd }) {
  const server = `${host}:${port}`
  const bot = mineflayer.createBot({ username: name, host, port, auth: 'offline' })
  const log = line => console.log(`[${name}] ${line}`)

  // "cx,cz" -> { key, claim }: the columns claimed and not unloaded, with the dimension they were claimed in.
  const columns = new Map()
  let nextClaim = 1

  // Everything goes out through this chain, so a frame waiting on a worker keeps its place.
  let chain = Promise.resolve()
  const out = frame => { chain = chain.then(() => frame).then(send) }

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

  function held (x, z) {
    const column = columns.get(`${x >> 4},${z >> 4}`)
    if (!column) throw new Error(`${name}: change at ${x},${z} in a column it never claimed`)
    return column
  }

  bot.loadPlugin(pathfinder)
  bot.once('spawn', () => {
    bot.pathfinder.setMovements(new Movements(bot))
    log('spawned')
  })
  bot.on('path_update', result => { if (result.status === 'noPath') log('formation: no path') })

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
    const id = `${column.key.x},${column.key.z}`
    let batch = batches.get(id)
    if (!batch) batches.set(id, (batch = { key: column.key, changes: [] }))
    const { stateName, stateIdOf } = blockStates()
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
    log('disconnected: ' + reason)
    flush()
    columns.clear()
    out(protocol.botGone(name))
    onEnd()
  })

  return {
    quit: () => bot.quit(),

    // Walks to the block, or to the nearest standable one around it that free(key) accepts
    // (breadth first, one step sideways and up to one up or down). Returns the key of the spot taken.
    formation ({ x, y, z }, free) {
      const target = new Vec3(x, y, z)
      if (!bot.blockAt(target)) throw new Error(`${name}: formation target ${x},${y},${z} is not loaded`)
      const keyOf = p => `${server}|${dimension()}|${p.x},${p.y},${p.z}`
      const seen = new Set([target.toString()])
      const queue = [target]
      for (let i = 0; i < queue.length && i < 4096; i++) {
        const p = queue[i]
        if (standable(p) && free(keyOf(p))) {
          bot.pathfinder.setGoal(new goals.GoalBlock(p.x, p.y, p.z))
          return keyOf(p)
        }
        for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
          for (const dy of [0, 1, -1]) {
            const n = p.offset(dx, dy, dz)
            if (seen.has(n.toString()) || !bot.blockAt(n)) continue
            seen.add(n.toString())
            queue.push(n)
          }
        }
      }
      throw new Error(`${name}: no free block to stand on around ${x},${y},${z}`)
    },

    // The registry wants the blocks of the column claimed as `claim`; a claim since unloaded is
    // already reported, and the registry asks the next holder instead.
    sendChunk (key, claim) {
      const column = columns.get(`${key.x},${key.z}`)
      if (!column || column.claim !== claim) return
      flush()
      const raw = bot.world.getColumn(key.x, key.z)
      if (!raw) throw new Error(`${name}: claimed column ${key.x},${key.z} is not loaded`)
      out(serialize({ bot: name, key: column.key, version: bot.version, column: raw.toJson() }))
    }
  }
}

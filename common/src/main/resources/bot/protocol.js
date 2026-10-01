// The binary frames between the fleet and the mod, mirrored by net.mapmcbot.fleet.FleetProtocol:
// u32 length, u8 type, payload; big-endian; strings are a u16 byte length and UTF-8. Chunk messages
// start with the header: bot, server (host:port), dimension, chunk x, chunk z. The columns' blocks
// never go through here: the mod reads them off their slot of columns.bin (see sharedChunks.js).
const TYPES = {
  // fleet -> mod
  CLAIM: 1,
  READY: 2,
  CHANGED: 3,
  BLOCK_ENTITY_UPDATE: 4,
  UNLOAD: 5,
  BOT_GONE: 6,
  PATH: 7,
  BOT_SPAWNED: 8,
  STATE_NAMES: 9,
  LOAD: 10,
  PROFILE: 11,
  // mod -> fleet
  RELEASE: 16,
  LOADED: 20,
  SPAWN: 17,
  QUIT: 18,
  FORMATION: 19,
  PROFILE_REQUEST: 21
}

class Writer {
  constructor (type) {
    // The length goes first, filled in by frame(): the frame is put together in one copy.
    this.length = Buffer.allocUnsafe(4)
    this.parts = [this.length]
    this.u8(type)
  }

  u8 (value) {
    const b = Buffer.allocUnsafe(1)
    b.writeUInt8(value)
    this.parts.push(b)
  }

  u16 (value) {
    const b = Buffer.allocUnsafe(2)
    b.writeUInt16BE(value)
    this.parts.push(b)
  }

  i32 (value) {
    const b = Buffer.allocUnsafe(4)
    b.writeInt32BE(value)
    this.parts.push(b)
  }

  str (value) {
    const bytes = Buffer.from(value, 'utf8')
    if (bytes.length > 0xffff) throw new Error(`string of ${bytes.length} bytes is too long`)
    this.u16(bytes.length)
    this.parts.push(bytes)
  }

  bytes (buffer) {
    this.i32(buffer.length)
    this.parts.push(buffer)
  }

  raw (buffer) {
    this.parts.push(buffer)
  }

  header (bot, key) {
    this.str(bot)
    this.str(key.server)
    this.str(key.dimension)
    this.i32(key.x)
    this.i32(key.z)
  }

  frame () {
    const frame = Buffer.concat(this.parts)
    frame.writeUInt32BE(frame.length - 4, 0)
    return frame
  }
}

class Reader {
  constructor (buffer) {
    this.buffer = buffer
    this.offset = 0
  }

  u8 () {
    return this.buffer.readUInt8(this.offset++)
  }

  u16 () {
    const value = this.buffer.readUInt16BE(this.offset)
    this.offset += 2
    return value
  }

  i32 () {
    const value = this.buffer.readInt32BE(this.offset)
    this.offset += 4
    return value
  }

  str () {
    const length = this.u16()
    if (this.offset + length > this.buffer.length) throw new Error('truncated string')
    const value = this.buffer.toString('utf8', this.offset, this.offset + length)
    this.offset += length
    return value
  }

  key () {
    return { server: this.str(), dimension: this.str(), x: this.i32(), z: this.i32() }
  }

  end (type) {
    if (this.offset !== this.buffer.length) throw new Error(`${this.buffer.length - this.offset} stray bytes after message type ${type}`)
  }
}

// id: this load's claim id; slot: its slot in columns.bin, the mod's until it releases it.
function claim (bot, key, id, slot, mcVersion) {
  const w = new Writer(TYPES.CLAIM)
  w.header(bot, key)
  w.i32(id)
  w.i32(slot)
  w.str(mcVersion)
  return w.frame()
}

// The column claimed as `id` is loaded into its slot.
function ready (bot, key, id) {
  const w = new Writer(TYPES.READY)
  w.header(bot, key)
  w.i32(id)
  return w.frame()
}

// The readied column's blocks or block entities changed: the mod snapshots it again.
function changed (bot, key) {
  const w = new Writer(TYPES.CHANGED)
  w.header(bot, key)
  return w.frame()
}

// Asks the mod to read the column's snapshot into the slot; answered with LOADED. No bot: an empty name.
function load (key, slot) {
  const w = new Writer(TYPES.LOAD)
  w.header('', key)
  w.i32(slot)
  return w.frame()
}

// names: [[state id, name]], every state id a slot may hold. No bot: an empty name.
function stateNames (names) {
  const w = new Writer(TYPES.STATE_NAMES)
  w.str('')
  w.i32(names.length)
  for (const [id, name] of names) {
    w.u16(id)
    w.str(name)
  }
  return w.frame()
}

// entity: { type, nbt: Buffer }, or null when the block entity was removed. Position in world coordinates.
function blockEntityUpdate (bot, key, pos, entity) {
  const w = new Writer(TYPES.BLOCK_ENTITY_UPDATE)
  w.header(bot, key)
  w.i32(pos.x)
  w.i32(pos.y)
  w.i32(pos.z)
  w.u8(entity ? 1 : 0)
  if (entity) {
    w.str(entity.type)
    w.bytes(entity.nbt)
  }
  return w.frame()
}

function unload (bot, key) {
  const w = new Writer(TYPES.UNLOAD)
  w.header(bot, key)
  return w.frame()
}

function botGone (bot) {
  const w = new Writer(TYPES.BOT_GONE)
  w.str(bot)
  return w.frame()
}

function botSpawned (bot) {
  const w = new Writer(TYPES.BOT_SPAWNED)
  w.str(bot)
  return w.frame()
}

// The path the bot is walking: target {x, y, z} and nodes [{x, y, z}]; null when it is not walking one.
function path (bot, target, nodes) {
  const w = new Writer(TYPES.PATH)
  w.str(bot)
  w.u8(target ? 1 : 0)
  if (target) {
    w.i32(target.x)
    w.i32(target.y)
    w.i32(target.z)
    w.i32(nodes.length)
    // One buffer for every node: a path is sent on each of its updates.
    const coords = Buffer.allocUnsafe(nodes.length * 12)
    nodes.forEach((n, i) => {
      coords.writeInt32BE(Math.floor(n.x), i * 12)
      coords.writeInt32BE(Math.floor(n.y), i * 12 + 4)
      coords.writeInt32BE(Math.floor(n.z), i * 12 + 8)
    })
    w.raw(coords)
  }
  return w.frame()
}

// The fleet's profile (see profiler.js): `id` the PROFILE_REQUEST's, 0 when the fleet took it on an
// event of its own, `reason` what it was taken for, `text` its rows. No bot: an empty name.
function profile (id, reason, text) {
  const w = new Writer(TYPES.PROFILE)
  w.str('')
  w.i32(id)
  w.str(reason)
  w.bytes(Buffer.from(text, 'utf8'))
  return w.frame()
}

// A mod -> fleet frame (without its length) as an object.
function decode (frame) {
  const r = new Reader(frame)
  const type = r.u8()
  let message
  switch (type) {
    case TYPES.RELEASE:
      message = { type, bot: r.str(), slot: r.i32() }
      break
    case TYPES.LOADED:
      // version: the snapshot's Minecraft version, null when the mod has none.
      message = { type, bot: r.str(), key: r.key(), slot: r.i32() }
      message.version = r.u8() ? r.str() : null
      break
    case TYPES.SPAWN:
      message = { type, bot: r.str(), host: r.str(), port: r.i32() }
      break
    case TYPES.QUIT:
      message = { type, bot: r.str() }
      break
    case TYPES.FORMATION:
      message = { type, bot: r.str(), id: r.i32(), x: r.i32(), y: r.i32(), z: r.i32() }
      break
    case TYPES.PROFILE_REQUEST:
      message = { type, bot: r.str(), id: r.i32(), reason: r.str() }
      break
    default:
      throw new Error(`unknown message type ${type} from the mod`)
  }
  r.end(type)
  return message
}

// A socket 'data' listener that calls onFrame with each whole frame, length stripped.
function frames (onFrame) {
  let pending = Buffer.alloc(0)
  return chunk => {
    pending = pending.length ? Buffer.concat([pending, chunk]) : chunk
    while (pending.length >= 4) {
      const length = pending.readUInt32BE(0)
      if (length === 0) throw new Error('empty frame from the mod')
      if (pending.length < 4 + length) break
      onFrame(pending.subarray(4, 4 + length))
      pending = pending.subarray(4 + length)
    }
  }
}

module.exports = { TYPES, claim, ready, changed, load,stateNames,blockEntityUpdate, unload, botGone, botSpawned, path, profile, decode, frames }

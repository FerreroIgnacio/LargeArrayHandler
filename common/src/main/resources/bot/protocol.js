// The binary frames between the fleet and the mod, mirrored by net.mapmcbot.chunk.ChunkProtocol:
// u32 length, u8 type, payload; big-endian; strings are a u16 byte length and UTF-8. Chunk messages
// start with the header: bot, server (host:port), dimension, chunk x, chunk z.
const TYPES = {
  // fleet -> mod
  CLAIM: 1,
  CHUNK_DATA: 2,
  BLOCK_UPDATE: 3,
  BLOCK_ENTITY_UPDATE: 4,
  UNLOAD: 5,
  BOT_GONE: 6,
  PATH: 7,
  BOT_SPAWNED: 8,
  // mod -> fleet
  REQUEST_CHUNK: 16,
  SPAWN: 17,
  QUIT: 18,
  FORMATION: 19
}

class Writer {
  constructor (type) {
    this.parts = []
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
    const body = Buffer.concat(this.parts)
    const length = Buffer.allocUnsafe(4)
    length.writeUInt32BE(body.length)
    return Buffer.concat([length, body])
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

function claim (bot, key, id) {
  const w = new Writer(TYPES.CLAIM)
  w.header(bot, key)
  w.i32(id)
  return w.frame()
}

// data: { minY, height, sections: [{ palette: [string], indices: Uint16Array(4096) }],
//         blockEntities: [{ x, y, z (local), type, nbt: Buffer }] }
function chunkData (bot, key, mcVersion, data) {
  const w = new Writer(TYPES.CHUNK_DATA)
  w.header(bot, key)
  w.str(mcVersion)
  w.i32(data.minY)
  w.i32(data.height)
  for (const section of data.sections) {
    const size = section.palette.length
    if (size < 1 || size > 0xffff) throw new Error(`section palette of ${size} states`)
    w.u16(size)
    for (const state of section.palette) w.str(state)
    if (size === 1) continue
    const wide = size > 256
    const indices = Buffer.allocUnsafe(section.indices.length * (wide ? 2 : 1))
    for (let i = 0; i < section.indices.length; i++) {
      if (wide) indices.writeUInt16BE(section.indices[i], i * 2)
      else indices[i] = section.indices[i]
    }
    w.raw(indices)
  }
  w.i32(data.blockEntities.length)
  for (const entity of data.blockEntities) {
    w.u8(entity.x)
    w.i32(entity.y)
    w.u8(entity.z)
    w.str(entity.type)
    w.bytes(entity.nbt)
  }
  return w.frame()
}

// changes: [{ x, y, z (world), state }]
function blockUpdate (bot, key, changes) {
  const w = new Writer(TYPES.BLOCK_UPDATE)
  w.header(bot, key)
  w.i32(changes.length)
  for (const change of changes) {
    w.i32(change.x)
    w.i32(change.y)
    w.i32(change.z)
    w.str(change.state)
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
    for (const n of nodes) {
      w.i32(Math.floor(n.x))
      w.i32(Math.floor(n.y))
      w.i32(Math.floor(n.z))
    }
  }
  return w.frame()
}

// A mod -> fleet frame (without its length) as an object.
function decode (frame) {
  const r = new Reader(frame)
  const type = r.u8()
  let message
  switch (type) {
    case TYPES.REQUEST_CHUNK:
      message = { type, bot: r.str(), key: r.key(), claim: r.i32() }
      break
    case TYPES.SPAWN:
      message = { type, bot: r.str(), host: r.str(), port: r.i32() }
      break
    case TYPES.QUIT:
      message = { type, bot: r.str() }
      break
    case TYPES.FORMATION:
      message = { type, bot: r.str(), x: r.i32(), y: r.i32(), z: r.i32() }
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

module.exports = { TYPES, claim, chunkData, blockUpdate, blockEntityUpdate, unload, botGone, botSpawned, path, decode, frames }

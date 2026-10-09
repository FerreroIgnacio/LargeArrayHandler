// Chunk columns shared by every bot of the fleet, across the pool's threads and with the mod. A
// column's blocks and biomes live in one slot of a file every thread maps (columns.bin, made by the
// mod, which reads the slots straight off it), handed out by the fleet's index (see fleet.js): the
// first bot to get a column has it parsed into its slot, every other bot, on any thread, sees that
// same slot. Whether it is loaded yet and its sections live in a small SharedArrayBuffer of the
// column: Atomics.waitAsync does not work on a mapped file. Each bot still has its own mineflayer
// World holding the column, so its events (chunkColumnLoad, blockUpdate...) stay its own.
// Pre-flattening columns only (1.9 to 1.12).
//
// No bot's thread ever blocks on a column, nor parses one: the slot comes from the fleet as a
// message, the packets are parsed into it by the serializer threads (columnSerializer.js), and the
// bots on other threads wait for it with Atomics.waitAsync. A bot's packets about its world go
// through in the order they came, each once the columns before it are in.
//
// Light is read off the packet and dropped: neither mineflayer's movement nor pathfinder looks at
// it, and it was a third of every column. Every block reads as fully lit.
const fs = require('fs')
const mmap = require('@riaskov/mmap-io')
const { SmartBuffer } = require('smart-buffer')
const { Vec3 } = require('vec3')
const BitArray = require('prismarine-chunk/src/pc/common/BitArray')
const varInt = require('prismarine-chunk/src/pc/common/varInt')
const neededBits = require('prismarine-chunk/src/pc/common/neededBits')
const CommonChunkColumn = require('prismarine-chunk/src/pc/common/CommonChunkColumn')

const SECTIONS = 16
const VOLUME = 4096
// Section palettes up to this many bits; past it the data holds global state ids.
const MAX_BITS_PER_BLOCK = 8

// Slot layout, little-endian, mirrored by net.mapmcbot.fleet.FleetProtocol: a header (unused, the
// column's SharedArrayBuffer holds it), the state id of every block (u16, index y << 8 | z << 4 | x),
// biomes (u8, z << 4 | x). A section the column lacks is zeros (air), as the mod reads it.
const STATES = 16
const BIOMES = STATES + SECTIONS * VOLUME * 2
const SIZE = BIOMES + 256
const SLOTS = 4096
// The column's SharedArrayBuffer: i32 ready, i32 section mask.
const READY = 0
const MASK = 1
const HEADER = 8

// The header of a column read into its slot whole (off its snapshot, by the mod): ready, every section there.
function loadedHeader () {
  const header = new SharedArrayBuffer(HEADER)
  const fields = new Int32Array(header)
  Atomics.store(fields, MASK, 0xffff)
  Atomics.store(fields, READY, 1)
  return header
}

// This thread's map of columns.bin (a SharedArrayBuffer over the file), once openColumns took it.
let mapped = null

function openColumns (file) {
  if (mapped) throw new Error(`columns already mapped on this thread, asked again for ${file}`)
  const fd = fs.openSync(file, 'r+')
  const size = fs.fstatSync(fd).size
  if (size !== SLOTS * SIZE) throw new Error(`${file} is ${size} bytes, not ${SLOTS} slots of ${SIZE}`)
  mapped = mmap.map(size, mmap.PROT_READ | mmap.PROT_WRITE, mmap.MAP_SHARED, fd, 0)
  if (!(mapped instanceof SharedArrayBuffer) || mapped.byteLength !== size) throw new Error(`mapping ${file} gave no SharedArrayBuffer of ${size} bytes`)
}

const index = pos => (pos.y << 8) | (pos.z << 4) | pos.x

// The slot's states and biomes on this thread's map.
function slotViews (slot) {
  if (!mapped) throw new Error('columns.bin is not mapped on this thread (openColumns)')
  if (!Number.isInteger(slot) || slot < 0 || slot >= SLOTS) throw new Error(`column slot ${slot} outside 0..${SLOTS - 1}`)
  const base = slot * SIZE
  return {
    states: new Uint16Array(mapped, base + STATES, SECTIONS * VOLUME),
    biomes: new Uint8Array(mapped, base + BIOMES, 256)
  }
}

// A map_chunk packet's data into the slot, as prismarine-chunk's 1.9 load reads it; the serializer's
// work (see columnSerializer.js). fresh: the slot's first load, which may hold a column the mod let
// go, so what the packet leaves out reads as air. header: the column's Int32Array, its sections set
// as they are written.
function writeColumn ({ slot, header, data, bitMap, skyLightSent, fullChunk, fresh, maxBitsPerBlock }) {
  const { states, biomes } = slotViews(slot)
  if (fresh) {
    states.fill(0)
    biomes.fill(1)
  }
  const reader = SmartBuffer.fromBuffer(Buffer.from(data.buffer, data.byteOffset, data.byteLength))
  // Block light, and sky light when sent: a nibble per block each, skipped.
  const light = (skyLightSent ? 2 : 1) * VOLUME / 2

  for (let y = 0; y < SECTIONS; y++) {
    if (!((bitMap >> y) & 1)) continue

    const bitsPerBlock = reader.readUInt8()
    let palette = null
    if (bitsPerBlock <= MAX_BITS_PER_BLOCK) {
      palette = []
      const size = varInt.read(reader)
      for (let i = 0; i < size; i++) palette.push(varInt.read(reader))
    } else {
      // The empty palette of a section on the global one.
      varInt.read(reader)
    }

    const blocks = new BitArray({
      bitsPerValue: bitsPerBlock > MAX_BITS_PER_BLOCK ? maxBitsPerBlock : bitsPerBlock,
      capacity: VOLUME
    }).readBuffer(reader, varInt.read(reader) * 2)
    if (reader.remaining() < light) throw new Error(`map_chunk section ${y} of slot ${slot} is missing its light`)
    reader.readOffset += light

    // Plain writes: the bots waiting on the column read it after READY, which publishes them.
    const base = y * VOLUME
    for (let i = 0; i < VOLUME; i++) {
      const value = blocks.get(i)
      states[base + i] = palette ? palette[value] : value
    }
    Atomics.or(header, MASK, 1 << y)
  }

  if (fullChunk) {
    for (let i = 0; i < 256; i++) biomes[i] = reader.readUInt8()
  }
}

// Loaded: the bots waiting on it, on any thread, can read it.
function markReady (header) {
  Atomics.store(header, READY, 1)
  Atomics.notify(header, READY)
}

// prismarine-chunk's 1.9 ChunkColumn API over a shared buffer.
function columnClass (registry) {
  const Block = require('prismarine-block')(registry)
  const Biome = require('prismarine-biome')(registry)
  const maxBitsPerBlock = neededBits(Object.values(registry.blocks).reduce((high, block) => Math.max(high, block.maxStateId), 0))

  // State id -> a Block built once, copied for each getBlock: Block's constructor walks the block's
  // variations and properties, on every one of the many blocks physics and the path searches read.
  const templates = new Map()
  function blockOf (stateId, biome) {
    let template = templates.get(stateId)
    if (!template) templates.set(stateId, (template = new Block(stateId >> 4, 0, stateId & 15)))
    const block = Object.assign(Object.create(Block.prototype), template)
    // Its own: getProperties merges computedStates into _properties.
    block._properties = { ...template._properties }
    block.computedStates = {}
    block.biome = Biome(biome)
    return block
  }

  return class SharedColumn extends CommonChunkColumn {
    // The bits of a state id on the global palette, for the serializer to read the packets with.
    static maxBitsPerBlock = maxBitsPerBlock

    // header: the column's SharedArrayBuffer; slot: its slot in columns.bin.
    constructor (header, slot) {
      super(registry)
      this.slot = slot
      this.headerBuffer = header
      this.header = new Int32Array(header, 0, 2)
      const { states, biomes } = slotViews(slot)
      this.states = states
      this.biomes = biomes
    }

    // Resolves once it is loaded into its slot, on whatever thread: never blocks this one.
    async whenReady () {
      const wait = Atomics.waitAsync(this.header, READY, 0)
      if (wait.async) await wait.value
    }

    has (y) {
      return y >= 0 && y < 256 && ((Atomics.load(this.header, MASK) >> (y >> 4)) & 1) === 1
    }

    // mineflayer's findBlocks only looks for null (empty) sections and palettes.
    get sections () {
      const mask = Atomics.load(this.header, MASK)
      return Array.from({ length: SECTIONS }, (_, y) => ((mask >> y) & 1) ? { palette: null } : null)
    }

    getMask () {
      return Atomics.load(this.header, MASK)
    }

    getBlock (pos) {
      const block = blockOf(this.getBlockStateId(pos), this.getBiome(pos))
      block.light = 15
      block.skyLight = 15
      block.entity = this.getBlockEntity(pos)
      return block
    }

    setBlock (pos, block) {
      if (block.type !== undefined) this.setBlockType(pos, block.type)
      if (block.metadata !== undefined) this.setBlockData(pos, block.metadata)
      if (block.biome !== undefined) this.setBiome(pos, block.biome.id)
      if (block.entity) this.setBlockEntity(pos, block.entity)
      else this.removeBlockEntity(pos)
    }

    getBlockStateId (pos) {
      return this.has(pos.y) ? this.states[index(pos)] : 0
    }

    getBlockType (pos) {
      return this.getBlockStateId(pos) >> 4
    }

    getBlockData (pos) {
      return this.getBlockStateId(pos) & 15
    }

    // Light is not kept (see the top of the file).
    getBlockLight (pos) {
      return 15
    }

    getSkyLight (pos) {
      return 15
    }

    getBiome (pos) {
      return this.biomes[(pos.z << 4) | pos.x]
    }

    setBlockStateId (pos, stateId) {
      if (pos.y < 0 || pos.y >= 256) return
      if (!this.has(pos.y)) {
        // Air in a missing section is already there.
        if (stateId === 0) return
        Atomics.or(this.header, MASK, 1 << (pos.y >> 4))
      }
      Atomics.store(this.states, index(pos), stateId)
    }

    setBlockType (pos, id) {
      this.setBlockStateId(pos, (id << 4) | this.getBlockData(pos))
    }

    setBlockData (pos, data) {
      this.setBlockStateId(pos, (this.getBlockType(pos) << 4) | data)
    }

    setBlockLight (pos, light) {}

    setSkyLight (pos, light) {}

    setBiome (pos, biome) {
      this.biomes[(pos.z << 4) | pos.x] = biome
    }

    // Part of the 1.9 API, and as there, they do nothing.
    loadBiomes () {}
    loadLight () {}
  }
}

// One pool thread's side: its bots' columns, asked of the fleet over `port`, which answers on it
// (never waited on: the bots go on meanwhile). `post(message)` sends to the fleet over that same port
// after the thread's frames so far: the fleet claims, readies and unloads each column in the mod as
// the fleet's first bot gets it, has loaded it and its last lets it go, so those have to land in
// order with the block entities the thread's bots report. `serializers`: a port to each serializer
// thread, which parse the packets into the slots.
function sharedChunks (port, post, serializers) {
  // key -> { holders, reply: resolves to { column, load } once the fleet answered, settled: resolves
  // once the column is loaded and, when this thread loaded it, readied to the fleet (settle), column }:
  // the columns some bot of this thread holds or asked for, and those bots.
  const columns = new Map()
  const classes = new Map()
  // key -> resolve of the fleet's answer, for the columns asked and not answered yet.
  const replies = new Map()

  port.on('message', message => {
    const resolve = replies.get(message.acquired)
    if (!resolve) throw new Error(`the fleet answered column ${message.acquired}, which this thread did not ask for`)
    replies.delete(message.acquired)
    resolve(message)
  })

  // Writes waiting on their serializer: id -> resolve.
  const writes = new Map()
  let nextWrite = 1
  for (const serializer of serializers) {
    serializer.on('message', ({ id }) => {
      const resolve = writes.get(id)
      if (!resolve) throw new Error(`a serializer finished write ${id}, which was not asked for`)
      writes.delete(id)
      resolve()
    })
  }

  // The packet's data into the column's slot, by the serializer of the slot (every write of a
  // column on the same one, in order). first: its first load, readied once written.
  function write (column, packet, skyLightSent, first) {
    const id = nextWrite++
    return new Promise(resolve => {
      writes.set(id, resolve)
      serializers[column.slot % serializers.length].postMessage({
        id,
        slot: column.slot,
        header: column.headerBuffer,
        data: packet.chunkData,
        bitMap: packet.bitMap,
        skyLightSent,
        fullChunk: packet.groundUp,
        fresh: first,
        ready: first,
        maxBitsPerBlock: column.constructor.maxBitsPerBlock
      })
    })
  }

  // The thread's column for the key, asked of the fleet if no bot of the thread holds it yet.
  // Resolves to { column, load }: load when the caller is the first bot anywhere to get it, which has
  // it written; anyone else gets it once it is loaded.
  async function acquire (key, registry, name) {
    let entry = columns.get(key)
    let first = false
    if (!entry) {
      first = true
      const version = registry.version.minecraftVersion
      let Column = classes.get(version)
      if (!Column) classes.set(version, (Column = columnClass(registry)))
      entry = { holders: new Set(), column: null }
      entry.settled = new Promise(resolve => { entry.settle = resolve })
      entry.reply = new Promise(resolve => replies.set(key, resolve)).then(({ header, slot, load }) => {
        entry.column = new Column(header, slot)
        return { column: entry.column, load }
      })
      columns.set(key, entry)
      post({ acquire: key, version, bot: name })
    }
    entry.holders.add(name)
    const { column, load } = await entry.reply
    if (first && load) return { column, load: true, entry }
    if (first) column.whenReady().then(entry.settle)
    await column.whenReady()
    return { column, load: false, entry }
  }

  // Let go once its load is done: the fleet never sees a column readied by a thread that let it go.
  function release (key, name) {
    const entry = columns.get(key)
    if (!entry || !entry.holders.delete(name)) throw new Error(`${name} released column ${key} it does not hold`)
    if (entry.holders.size > 0) return
    entry.settled.then(() => {
      if (entry.holders.size > 0 || columns.get(key) !== entry) return
      columns.delete(key)
      post({ release: key, bot: name })
    })
  }

  return {
    // The thread's column for the key, undefined when no bot of the thread holds it or it is not in yet.
    get (key) {
      return columns.get(key)?.column ?? undefined
    },

    // Takes over the bot's map_chunk handling (mineflayer's blocks plugin) once it is injected.
    // world(): the bot's "server|dimension"; blockEntity(tag) gets each block entity of a packet
    // this bot wrote into the column, once the column is in its world, for the mod. meter: the bot's
    // (see profiler.js), timing what runs off the packet events.
    attach (bot, name, world, blockEntity, meter) {
      // "cx,cz" -> key: the columns in the bot's world, from the moment they are asked for.
      const held = new Map()

      async function mapChunk (packet) {
        const { x, z } = packet
        // An empty full column unloads it, as in mineflayer.
        if (packet.groundUp && !packet.bitMap) {
          bot.world.unloadColumn(x, z)
          return
        }
        const id = `${x},${z}`
        const key = `${world()}|${id}`
        const skyLightSent = bot.game.dimension === 'overworld'
        let column
        let first = false
        let wrote
        if (held.get(id) === key) {
          // Sent again: over the one there.
          column = columns.get(key).column
          wrote = true
          await write(column, packet, skyLightSent, false)
        } else {
          if (held.has(id)) throw new Error(`${name}: column ${id} still held from ${held.get(id)}`)
          held.set(id, key)
          const acquired = await acquire(key, bot.registry, name)
          column = acquired.column
          first = acquired.load
          // A partial column updates the shared one, whoever loaded it.
          wrote = first || packet.groundUp === false
          if (first) {
            await write(column, packet, skyLightSent, true)
          } else if (wrote) {
            await write(column, packet, skyLightSent, false)
          }
        }
        meter.run(() => {
          const tags = packet.blockEntities ?? []
          for (const tag of tags) {
            column.setBlockEntity(new Vec3(tag.value.x.value & 0xf, tag.value.y.value, tag.value.z.value & 0xf), tag)
          }
          bot.world.setColumn(x, z, column)
          // The blocks reach the mod through the slot; the block entities of what this bot wrote do not.
          if (wrote) for (const tag of tags) blockEntity(tag)
          // After those (post sends the frames so far first): the mod reads the slot from here on and
          // snapshots it, block entities and all. Written over since: snapshotted again.
          if (first) post({ ready: key, bot: name })
          else if (wrote) post({ changed: key, bot: name })
        })
        if (first) columns.get(key).settle()
      }

      // The bot's packets about its world, in the order they came: each runs once the map_chunk
      // before it is in (its own listeners, mineflayer's included, untouched otherwise).
      const queue = []
      let busy = false
      function run (handle) {
        if (busy || queue.length > 0) {
          queue.push(handle)
          return
        }
        start(handle)
      }
      function start (handle) {
        const result = handle()
        if (!(result instanceof Promise)) return
        busy = true
        result.then(drain, err => { throw err })
      }
      function drain () {
        busy = false
        while (!busy && queue.length > 0) start(queue.shift())
      }

      bot.once('inject_allowed', () => {
        const version = bot.registry.version
        if (!version['>=']('1.9') || !version['<']('1.13')) {
          throw new Error(`${name}: shared chunks only know 1.9 to 1.12 columns, not ${version.minecraftVersion}`)
        }
        bot._client.removeAllListeners('map_chunk')
        bot._client.on('map_chunk', packet => run(() => mapChunk(packet)))
        for (const packetName of WORLD_PACKETS) {
          const listeners = bot._client.listeners(packetName)
          bot._client.removeAllListeners(packetName)
          bot._client.on(packetName, packet => run(() => {
            // Run off the packet event when it waited: timed as the bot's handlers all the same.
            meter.run(() => {
              for (const listener of listeners) listener.call(bot._client, packet)
            })
          }))
        }
      })

      bot.on('chunkColumnUnload', point => {
        const id = `${point.x >> 4},${point.z >> 4}`
        const key = held.get(id)
        if (!key) throw new Error(`${name}: unloaded column ${id} it does not hold`)
        held.delete(id)
        release(key, name)
      })

      // Its world goes with it: the columns it asked for and never got in too, once they are.
      bot.on('end', () => {
        for (const key of held.values()) release(key, name)
        held.clear()
      })
    }
  }
}

// The packets besides map_chunk that read or change a bot's world, which wait on the columns before them.
const WORLD_PACKETS = ['unload_chunk', 'block_change', 'multi_block_change', 'explosion', 'update_sign', 'tile_entity_data', 'block_action', 'respawn']

module.exports = { sharedChunks, columnClass, openColumns, loadedHeader, writeColumn, markReady, HEADER, SLOTS, SIZE }

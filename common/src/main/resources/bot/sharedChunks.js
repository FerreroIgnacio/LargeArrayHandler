// Chunk columns shared by every bot of the fleet, across the pool's threads. A column's blocks,
// light and biomes live in one SharedArrayBuffer, handed out by the fleet's index (see fleet.js):
// the first bot to get a column parses it into the buffer, every other bot, on any thread, sees
// that same buffer. Each bot still has its own mineflayer World holding the column, so its events
// (chunkColumnLoad, blockUpdate...) stay its own. Pre-flattening columns only (1.9 to 1.12).
const { receiveMessageOnPort } = require('worker_threads')
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

// Buffer layout: header (i32 ready, i32 section mask, i32 sky light sent), the state id of every
// block (u16, index y << 8 | z << 4 | x), block light and sky light (a nibble each), biomes (u8, z << 4 | x).
const READY = 0
const MASK = 1
const SKY = 2
const STATES = 16
const BLOCK_LIGHT = STATES + SECTIONS * VOLUME * 2
const SKY_LIGHT = BLOCK_LIGHT + SECTIONS * VOLUME / 2
const BIOMES = SKY_LIGHT + SECTIONS * VOLUME / 2
const SIZE = BIOMES + 256

const index = pos => (pos.y << 8) | (pos.z << 4) | pos.x

function nibble (array, i) {
  return (array[i >> 1] >> ((i & 1) << 2)) & 15
}

function setNibble (array, i, value) {
  const shift = (i & 1) << 2
  array[i >> 1] = (array[i >> 1] & ~(15 << shift)) | ((value & 15) << shift)
}

// prismarine-chunk's 1.9 ChunkColumn API over a shared buffer.
function columnClass (registry) {
  const Block = require('prismarine-block')(registry)
  const maxBitsPerBlock = neededBits(Object.values(registry.blocks).reduce((high, block) => Math.max(high, block.maxStateId), 0))

  return class SharedColumn extends CommonChunkColumn {
    constructor (buffer) {
      super(registry)
      this.buffer = buffer
      this.header = new Int32Array(buffer, 0, 3)
      this.states = new Uint16Array(buffer, STATES, SECTIONS * VOLUME)
      this.blockLight = new Uint8Array(buffer, BLOCK_LIGHT, SECTIONS * VOLUME / 2)
      this.skyLight = new Uint8Array(buffer, SKY_LIGHT, SECTIONS * VOLUME / 2)
      this.biomes = new Uint8Array(buffer, BIOMES, 256)
    }

    // Loaded: the bots waiting on it can read it.
    ready () {
      Atomics.store(this.header, READY, 1)
      Atomics.notify(this.header, READY)
    }

    // Blocks the thread until the bot loading it, on another thread, is done.
    waitReady () {
      Atomics.wait(this.header, READY, 0)
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
      const block = new Block(this.getBlockType(pos), this.getBiome(pos), this.getBlockData(pos))
      block.light = this.getBlockLight(pos)
      block.skyLight = this.getSkyLight(pos)
      block.entity = this.getBlockEntity(pos)
      return block
    }

    setBlock (pos, block) {
      if (block.type !== undefined) this.setBlockType(pos, block.type)
      if (block.metadata !== undefined) this.setBlockData(pos, block.metadata)
      if (block.biome !== undefined) this.setBiome(pos, block.biome.id)
      if (block.skyLight !== undefined && Atomics.load(this.header, SKY)) this.setSkyLight(pos, block.skyLight)
      if (block.light !== undefined) this.setBlockLight(pos, block.light)
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

    getBlockLight (pos) {
      return this.has(pos.y) ? nibble(this.blockLight, index(pos)) : 15
    }

    getSkyLight (pos) {
      if (!this.has(pos.y)) return 15
      return Atomics.load(this.header, SKY) ? nibble(this.skyLight, index(pos)) : 0
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

    setBlockLight (pos, light) {
      if (this.has(pos.y)) setNibble(this.blockLight, index(pos), light)
    }

    setSkyLight (pos, light) {
      if (this.has(pos.y) && Atomics.load(this.header, SKY)) setNibble(this.skyLight, index(pos), light)
    }

    setBiome (pos, biome) {
      this.biomes[(pos.z << 4) | pos.x] = biome
    }

    // A map_chunk packet's data, as prismarine-chunk's 1.9 load reads it.
    load (data, bitMap = 0xffff, skyLightSent = true, fullChunk = true) {
      const reader = SmartBuffer.fromBuffer(data)
      Atomics.store(this.header, SKY, skyLightSent ? 1 : 0)

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
        const blockLight = new BitArray({ bitsPerValue: 4, capacity: VOLUME }).readBuffer(reader)
        const skyLight = skyLightSent ? new BitArray({ bitsPerValue: 4, capacity: VOLUME }).readBuffer(reader) : null

        const base = y * VOLUME
        for (let i = 0; i < VOLUME; i++) {
          const value = blocks.get(i)
          Atomics.store(this.states, base + i, palette ? palette[value] : value)
          setNibble(this.blockLight, base + i, blockLight.get(i))
          if (skyLight) setNibble(this.skyLight, base + i, skyLight.get(i))
        }
        Atomics.or(this.header, MASK, 1 << y)
      }

      if (fullChunk) {
        for (let i = 0; i < 256; i++) this.biomes[i] = reader.readUInt8()
      }
    }

    // Part of the 1.9 API, and as there, they do nothing.
    loadBiomes () {}
    loadLight () {}
  }
}

// One pool thread's side: its bots' columns, asked of the fleet over `port`, the answer waited on
// with `signal` (an Int32Array on a SharedArrayBuffer the fleet sets once it posted the answer).
function sharedChunks (port, signal) {
  // key -> { column, holders }: the columns some bot of this thread holds, and those bots.
  const columns = new Map()
  const classes = new Map()

  // The thread's column for the key, asked of the fleet if no bot of the thread holds it yet.
  // load: the caller is the first bot anywhere to get it, and parses it into the buffer.
  function acquire (key, registry, name) {
    let entry = columns.get(key)
    let load = false
    if (!entry) {
      Atomics.store(signal, 0, 0)
      port.postMessage({ acquire: key })
      Atomics.wait(signal, 0, 0)
      const reply = receiveMessageOnPort(port)
      if (!reply) throw new Error(`the fleet signalled column ${key} without an answer`)
      const version = registry.version.minecraftVersion
      let Column = classes.get(version)
      if (!Column) classes.set(version, (Column = columnClass(registry)))
      entry = { column: new Column(reply.message.buffer), holders: new Set() }
      columns.set(key, entry)
      load = reply.message.load
      if (load) entry.column.biomes.fill(1)
      else entry.column.waitReady()
    }
    entry.holders.add(name)
    return { column: entry.column, load }
  }

  function release (key, name) {
    const entry = columns.get(key)
    if (!entry || !entry.holders.delete(name)) throw new Error(`${name} released column ${key} it does not hold`)
    if (entry.holders.size > 0) return
    columns.delete(key)
    port.postMessage({ release: key })
  }

  return {
    // Takes over the bot's map_chunk handling (mineflayer's blocks plugin) once it is injected.
    attach (bot, name, server) {
      // "cx,cz" -> key: the columns in the bot's world.
      const held = new Map()

      function mapChunk (packet) {
        const { x, z } = packet
        // An empty full column unloads it, as in mineflayer.
        if (packet.groundUp && !packet.bitMap) {
          bot.world.unloadColumn(x, z)
          return
        }
        const id = `${x},${z}`
        const key = `${server}|${bot.game.dimension}|${id}`
        let column
        let load
        if (held.get(id) === key) {
          // Sent again: over the one there.
          column = columns.get(key).column
          load = true
        } else {
          if (held.has(id)) throw new Error(`${name}: column ${id} still held from ${held.get(id)}`)
          ;({ column, load } = acquire(key, bot.registry, name))
          held.set(id, key)
        }
        // A partial column updates the shared one, whoever loaded it.
        if (load || packet.groundUp === false) {
          column.load(packet.chunkData, packet.bitMap, bot.game.dimension === 'overworld', packet.groundUp)
          column.ready()
        }
        for (const tag of packet.blockEntities ?? []) {
          column.setBlockEntity(new Vec3(tag.value.x.value & 0xf, tag.value.y.value, tag.value.z.value & 0xf), tag)
        }
        bot.world.setColumn(x, z, column)
      }

      bot.once('inject_allowed', () => {
        const version = bot.registry.version
        if (!version['>=']('1.9') || !version['<']('1.13')) {
          throw new Error(`${name}: shared chunks only know 1.9 to 1.12 columns, not ${version.minecraftVersion}`)
        }
        bot._client.removeAllListeners('map_chunk')
        bot._client.on('map_chunk', mapChunk)
      })

      bot.on('chunkColumnUnload', point => {
        const id = `${point.x >> 4},${point.z >> 4}`
        const key = held.get(id)
        if (!key) throw new Error(`${name}: unloaded column ${id} it does not hold`)
        held.delete(id)
        release(key, name)
      })

      // Its world goes with it.
      bot.on('end', () => {
        for (const key of held.values()) release(key, name)
        held.clear()
      })
    }
  }
}

module.exports = { sharedChunks, SIZE }

const nbt = require('prismarine-nbt')
const { Vec3 } = require('vec3')
const states = require('./states')

// A loaded prismarine-chunk column as the payload of a CHUNK_DATA frame; stateName from states.js.
module.exports = function serialize (chunk, stateName) {
  const minY = chunk.minY ?? 0
  const height = chunk.worldHeight ?? 256
  const pos = new Vec3(0, 0, 0)
  const sections = []

  for (let base = minY; base < minY + height; base += 16) {
    const palette = []
    const lookup = new Map()
    const indices = new Uint16Array(4096)
    // y, z, x order: i = y << 8 | z << 4 | x
    for (let i = 0; i < 4096; i++) {
      pos.x = i & 15
      pos.z = (i >> 4) & 15
      pos.y = base + (i >> 8)
      const state = stateName(chunk.getBlockStateId(pos))
      let index = lookup.get(state)
      if (index === undefined) {
        index = palette.length
        palette.push(state)
        lookup.set(state, index)
      }
      indices[i] = index
    }
    sections.push({ palette, indices })
  }

  const blockEntities = []
  for (const [where, tag] of Object.entries(chunk.blockEntities)) {
    // Keyed by position, local or world depending on the version; & 15 gives local either way.
    const [x, y, z] = where.split(',').map(Number)
    if (!tag || tag.type !== 'compound') throw new Error(`block entity at ${where} has no NBT compound`)
    pos.x = x & 15
    pos.y = y
    pos.z = z & 15
    blockEntities.push({
      x: pos.x,
      y,
      z: pos.z,
      type: states.blockName(stateName(chunk.getBlockStateId(pos))),
      nbt: nbt.writeUncompressed({ ...tag, name: tag.name ?? '' })
    })
  }

  return { minY, height, sections, blockEntities }
}

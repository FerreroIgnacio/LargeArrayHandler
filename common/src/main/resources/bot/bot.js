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
const actions = require('./actions')
const { botMeter } = require('./profiler')

// send(frame) writes to the mod, report(message) tells the fleet, onEnd() once the bot is gone and
// its last frame sent, onSearching(searching) each time its pathfinder starts or stops searching;
// chunks holds the columns of its thread (see sharedChunks.js).
module.exports = function startBot ({ name, host, port, chunks, send, report, onEnd, onSearching }) {
  const server = `${host}:${port}`
  // Always the fewest chunks it can ask for; a 1.12.2 server (a LAN world) sends its own view distance whatever it asks.
  const bot = mineflayer.createBot({ username: name, host, port, viewDistance: 2, auth: 'offline' })
  const log = line => console.log(`[${name}] ${line}`)
  // The time its handlers take on the thread: mineflayer's events and the client's packets.
  const meter = botMeter()
  meter.wrap(bot)
  meter.wrap(bot._client)
  // And apart, the time its packets take to be split, decompressed and parsed before any handler.
  meter.wrapDecode(bot._client.splitter)
  meter.wrapDecode(bot._client.deserializer)
  const setCompressionThreshold = bot._client.setCompressionThreshold
  bot._client.setCompressionThreshold = function (threshold) {
    setCompressionThreshold.call(this, threshold)
    if (this.decompressor) meter.wrapDecode(this.decompressor)
  }

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
  // The items' own class, prismarine-item's for the server's version.
  let itemClass
  const Item = () => (itemClass ??= require('prismarine-item')(bot.registry))

  // The bot's primitives and state (see actions.js); its pathfinder takes the goals they walk to.
  const act = actions({
    bot,
    name,
    log,
    publish: (kind, primitive, message) => out(protocol.state(name, { idle: 0, doing: 1, error: 2 }[kind], primitive, message)),
    walk: goal => {
      setSearching(true)
      log(`goto ${spot(goal)} from ${at()}`)
      bot.pathfinder.setGoal(goal)
    },
    // The villager window's trades as the server listed them, and the one picked shown in its result.
    trades: window => trades.id === window.id ? trades.recipes : null,
    selectTrade: (window, trade) => pickTrade(window, trade),
    rawClick,
    blockStates
  })

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
    const type = states.blockName(stateName(stateIdOf(bot.blockAt(new Vec3(location.x, location.y, location.z)))))
    out(protocol.blockEntityUpdate(name, key, location, {
      type,
      nbt: nbt.writeUncompressed({ ...tag, name: tag.name ?? '' })
    }))
    if (CHESTS.has(type)) out(protocol.chest(name, key, location, type))
  }

  // The blocks with an inventory the mod keeps (see protocol.chest), as they show up or go.
  const SHULKER_COLORS = ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'silver', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black']
  const CHESTS = new Set([
    'minecraft:chest', 'minecraft:trapped_chest', 'minecraft:hopper', 'minecraft:dispenser', 'minecraft:dropper',
    'minecraft:furnace', 'minecraft:lit_furnace', 'minecraft:brewing_stand',
    ...SHULKER_COLORS.map(color => `minecraft:${color}_shulker_box`)
  ])
  function reportChestChange (oldBlock, newBlock) {
    const { stateName, stateIdOf } = blockStates()
    const was = states.blockName(stateName(stateIdOf(oldBlock)))
    const is = states.blockName(stateName(stateIdOf(newBlock)))
    if (was === is) return
    if (CHESTS.has(is)) out(protocol.chest(name, held(newBlock.position.x, newBlock.position.z), newBlock.position, is))
    else if (CHESTS.has(was)) out(protocol.chest(name, held(newBlock.position.x, newBlock.position.z), newBlock.position, ''))
  }

  // Those of the map_chunk packets this bot loaded into the shared column.
  chunks.attach(bot, name, world, tag => reportBlockEntity({ x: tag.value.x.value, y: tag.value.y.value, z: tag.value.z.value }, tag), meter)

  bot.loadPlugin(pathfinder)
  // Pathfinder's time per tick, in ms, once the pool thread has shared its own out (see poolThread.js).
  let tickTimeout = null
  bot.once('spawn', () => {
    const movements = new Movements(bot)
    // Walking only: a bot breaking blocks resets the paths of every bot through them, and it has no
    // blocks to place (each try a place_error and a new search).
    movements.canDig = false
    movements.allow1by1towers = false
    movements.scafoldingBlocks = []
    bot.pathfinder.setMovements(movements)
    if (tickTimeout !== null) bot.pathfinder.tickTimeout = tickTimeout
    // Never a timeout: pathfinder walks a timed out search's best path and never searches again,
    // the bot left stuck with its goal. Without one a search goes on in parts (partial) while it
    // walks, until the goal or noPath (said in the log).
    bot.pathfinder.thinkTimeout = Infinity
    log('spawned')
    out(protocol.botSpawned(name))
    out(protocol.state(name, 0, '', ''))
  })
  // Every spawn, the first and each respawn or change of dimension: the fleet keeps where each bot is.
  bot.on('spawn', () => report({ world: world() }))
  // The mod draws the path being walked and its target; an empty PATH clears it.
  const goalOf = () => {
    const g = bot.pathfinder.goal
    return g ? { x: g.x, y: g.y, z: g.z } : null
  }
  // What the mod draws now: { target, coords: the nodes' blocks, x y z each }, null when nothing.
  // A PATH goes only when that changes: a search's partials are mostly the same path tick after
  // tick, and comparing them costs less than putting one in a frame.
  let drawn = null
  // The nodes of the last path and how many the bot has walked past: the mod draws only the rest.
  let walking = null
  let consumed = 0
  function drawPath (target, nodes) {
    if (walking && walking.target.x === target.x && walking.target.y === target.y && walking.target.z === target.z &&
        walking.nodes.length === nodes.length &&
        walking.nodes.every((n, i) => Math.floor(n.x) === Math.floor(nodes[i].x) && Math.floor(n.y) === Math.floor(nodes[i].y) && Math.floor(n.z) === Math.floor(nodes[i].z))) return
    walking = { target, nodes }
    consumed = 0
    sendPath(target, nodes)
  }
  function sendPath (target, nodes) {
    const coords = new Int32Array(nodes.length * 3)
    nodes.forEach((n, i) => {
      coords[i * 3] = Math.floor(n.x)
      coords[i * 3 + 1] = Math.floor(n.y)
      coords[i * 3 + 2] = Math.floor(n.z)
    })
    if (drawn && drawn.target.x === target.x && drawn.target.y === target.y && drawn.target.z === target.z &&
        drawn.coords.length === coords.length && drawn.coords.every((c, i) => c === coords[i])) return
    drawn = { target, coords }
    out(protocol.path(name, target, nodes))
  }
  // Each tick: nodes the bot stands within a block of (or past, the closest ahead) are consumed.
  bot.on('physicsTick', () => {
    if (!walking || !drawn) return
    const p = bot.entity.position
    const { target, nodes } = walking
    let best = consumed
    let bestD = Infinity
    for (let i = consumed; i < Math.min(nodes.length, consumed + 6); i++) {
      const n = nodes[i]
      const d = (n.x + 0.5 - p.x) ** 2 + (n.y - p.y) ** 2 + (n.z + 0.5 - p.z) ** 2
      if (d < bestD) { bestD = d; best = i }
    }
    if (bestD > 2.25 || best === consumed) return
    consumed = best
    if (consumed < nodes.length) sendPath(target, nodes.slice(consumed))
  })
  function clearPath () {
    walking = null
    if (!drawn) return
    drawn = null
    out(protocol.path(name, null))
  }
  // Whether its pathfinder is searching: from a goto or a reset until a search is over (success or
  // noPath) or the walk ends; a search pathfinder starts on its own shows as a partial.
  let searching = false
  const setSearching = now => {
    if (now === searching) return
    searching = now
    onSearching(now)
  }
  bot.on('path_update', result => {
    setSearching(result.status === 'partial')
    if (result.status === 'noPath') log('no path' + (result.reason ? ` (${result.reason})` : ''))
    // Every search once it is over, with how long it took (taking turns with the other searches of the
    // thread included); not its parts while it goes on, one a tick.
    if (result.status !== 'partial') log(`search: ${result.visitedNodes} nodes, done after ${Math.round(result.time)} ms (${result.status})`)
    const target = goalOf()
    if (result.status === 'noPath' || !target) clearPath()
    else drawPath(target, result.path)
  })
  // For debugging the paths drawn: where the bot stands and where it is going each time a path ends.
  const at = () => {
    const p = bot.entity.position
    return `${Math.floor(p.x)},${Math.floor(p.y)},${Math.floor(p.z)}`
  }
  const spot = g => g ? `${g.x ?? g.pos?.x},${g.y ?? g.pos?.y},${g.z ?? g.pos?.z}` : 'none'
  // The blocks around the feet (y-1, y, y+1 for each of the 3x3 columns), for a stuck bot's log.
  const around = () => {
    const p = bot.entity.position.floored()
    const rows = []
    for (let dy = -1; dy <= 2; dy++) {
      const row = []
      for (let dz = -1; dz <= 1; dz++) for (let dx = -1; dx <= 1; dx++) row.push(bot.blockAt(p.offset(dx, dy, dz))?.name ?? '?')
      rows.push(`y${dy >= 0 ? '+' : ''}${dy}: ${row.join(' ')}`)
    }
    return rows.join(' | ')
  }
  bot.on('goal_reached', goal => {
    setSearching(false)
    log(`goal reached at ${at()} (goal ${spot(goal)})`)
    clearPath()
  })
  bot.on('path_stop', () => {
    setSearching(false)
    log(`path stopped at ${at()} (goal ${spot(goalOf())})`)
    clearPath()
  })
  bot.on('path_reset', reason => {
    setSearching(true)
    log(`path reset (${reason}) at ${at()} (goal ${spot(goalOf())})`)
    if (reason === 'stuck') log(`stuck around (x-1..x+1 per z-1..z+1): ${around()}; vel ${bot.entity.velocity.toString()} onGround ${bot.entity.onGround}`)
    clearPath()
  })

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

  // A chest's lid: the mod tells a player's opening from its bots' own by how many have it open.
  bot.on('chestLidMove', (block, viewers) => out(protocol.chestLid(name, block.position, viewers)))

  bot.on('blockUpdate', (oldBlock, newBlock) => {
    // The column is shared: the first bot of the fleet to get the change already wrote it there.
    // The rest see no change, and the column is snapshotted once instead of once per bot.
    const { stateIdOf } = blockStates()
    if (stateIdOf(oldBlock) === stateIdOf(newBlock)) return
    markChanged(newBlock.position.x, newBlock.position.z)
    reportChestChange(oldBlock, newBlock)
  })

  // The bot's inventory for the mod (its bot window): sent whole once anything in it changed, a slot,
  // the item on its cursor or the one in its hand, once per turn however many did.
  let inventoryQueued = false
  // The id of the mod's last click done (see click below), 0 before any.
  let lastClick = 0
  const itemOut = it => it ? { name: it.name, count: it.count, metadata: it.metadata, nbt: it.nbt ? nbt.writeUncompressed({ ...it.nbt, name: it.nbt.name ?? '' }) : Buffer.alloc(0) } : null
  function sendInventory () {
    inventoryQueued = false
    if (!bot.inventory) return
    out(protocol.inventory(name, bot.quickBarSlot ?? 0, lastClick, itemOut(bot.inventory.selectedItem), bot.inventory.slots.map(itemOut)))
  }
  function queueInventory () {
    if (inventoryQueued) return
    inventoryQueued = true
    setImmediate(sendInventory)
  }
  bot.once('spawn', () => {
    bot.inventory.on('updateSlot', queueInventory)
    bot.on('heldItemChanged', queueInventory)
    // The server's own word on slots and the cursor, mineflayer's handlers run first.
    bot._client.on('set_slot', () => { queueInventory(); queueWindow() })
    bot._client.on('window_items', () => { queueInventory(); queueWindow() })
    queueInventory()
  })

  // A copy of the item with that many in it, null for none.
  const withCount = (item, count) => count === 0 ? null : Object.assign(Object.create(Object.getPrototypeOf(item)), item, { count })
  // The same item as the cursor's (the items' own class, prismarine-item's made for this version), the count aside.
  const stacksWith = (cursor, it) => cursor.constructor.equal(it, cursor, false)

  // The clicks prismarine-windows has no model of, a drag (5) and a double click (6), sent as the
  // client sends them: the item the server answers them with is always none, mineflayer's own
  // clickWindow would send the slot's. Each under an action number of its own, counting down from
  // -1 (mineflayer's count up from 1): mineflayer answers their transactions as ones it did not
  // send, accepted, which the server takes as nothing. Done once the server confirms it; a click it
  // rejects is fatal, as mineflayer's (its error rejected). Also the shift clicks, carrying item (see
  // actions.js shiftClick).
  let rawAction = 0
  const rawPending = new Map()
  bot._client.on('transaction', ({ windowId, action, accepted }) => {
    const pending = rawPending.get(action)
    if (!pending || pending.windowId !== windowId) return
    rawPending.delete(action)
    if (accepted) pending.resolve()
    else pending.reject(Object.assign(new Error(`${name}: the server rejected click ${pending.what} on window ${windowId}`), { rejected: true }))
  })
  function rawClick (window, slot, button, mode, item = { blockId: -1 }) {
    rawAction = rawAction === -32768 ? -1 : rawAction - 1
    const action = rawAction
    const done = new Promise((resolve, reject) => {
      rawPending.set(action, { windowId: window.id, what: `slot ${slot}, button ${button}, mode ${mode}`, resolve, reject })
    })
    bot._client.write('window_click', { windowId: window.id, slot, mouseButton: button, action, mode, item })
    return done
  }

  // A double click with an item on the cursor (pickup all, mode 6), worked out as the server does it
  // on the window as it is before it is sent, and returned to be done on it once confirmed: the server
  // sends the slots it emptied before its confirmation, so worked out then it would find them taken
  // already, the cursor left as it was. Over an empty slot the cursor takes the same item from every
  // other slot, up to a full stack, the partly filled stacks first and the full ones after, from the
  // first slot on (the last on, right button). The crafting result is left out.
  function pickupAll (window, slot, button) {
    const cursor = window.selectedItem
    if (!cursor || window.slots[slot]) return () => {}
    let count = cursor.count
    // Slot: [its item before, what it is left with].
    const taken = new Map()
    const order = window.slots.map((_, i) => i)
    if (button !== 0) order.reverse()
    for (const fullOnes of [false, true]) {
      for (const i of order) {
        if (count >= cursor.stackSize) break
        const it = window.slots[i]
        const left = taken.has(i) ? taken.get(i)[1] : it?.count
        if (i === window.craftingResultSlot || !it || left === 0 || !stacksWith(cursor, it)) continue
        if (!fullOnes && left === it.stackSize) continue
        const n = Math.min(cursor.stackSize - count, left)
        count += n
        taken.set(i, [it, left - n])
      }
    }
    return () => {
      for (const [i, [it, left]] of taken) window.updateSlot(i, withCount(it, left))
      window.selectedItem = withCount(cursor, count)
    }
  }

  // A drag's slot (stage 1) as the server takes it: the cursor holding something, the slot empty or
  // holding the same item, while the cursor has more than the slots taken (one each at least); the
  // crafting result is left out.
  function dragTakes (drag, slot) {
    const cursor = drag.window.selectedItem
    if (!cursor || slot < 0 || slot === drag.window.craftingResultSlot || drag.slots.includes(slot)) return false
    const it = drag.window.slots[slot]
    return (!it || stacksWith(cursor, it)) && cursor.count > drag.slots.length
  }

  // A drag's end (stage 2) as the server does it: the cursor split evenly over the slots taken
  // (limit 0) or one in each (limit 1), each slot as full as it takes, the rest left on the cursor.
  // The creative one (2) the server leaves undone but in creative, which the bot is never in.
  function dragOver ({ window, limit, slots }) {
    const cursor = window.selectedItem
    if (!cursor || slots.length === 0 || limit === 2 || cursor.count < slots.length) return
    const each = limit === 0 ? Math.floor(cursor.count / slots.length) : 1
    let left = cursor.count
    for (const slot of slots) {
      const it = window.slots[slot]
      if (it && !stacksWith(cursor, it)) continue
      const there = it ? it.count : 0
      const count = Math.min(cursor.stackSize, there + each)
      left -= count - there
      window.updateSlot(slot, withCount(cursor, count))
    }
    window.selectedItem = withCount(cursor, left)
  }

  // The mod's clicks on the bot's inventory, or on the window it has open when it has one (the slots
  // are then the window's own, the inventory's after them, as the mod numbers them), one after the other as a client's window takes them,
  // each id told back once done (lastClick). mineflayer clicks modes 0 to 4; a drag (5) and a double
  // click (6) are sent as the client sends them (see rawClick), what they leave worked out here.
  let clicks = Promise.resolve()
  let drag = null
  async function click (slot, button, mode) {
    const window = bot.currentWindow || bot.inventory
    if (mode === 5) {
      const stage = button & 3
      if (stage === 0) {
        if (drag) throw new Error(`${name}: a drag started inside a drag`)
        drag = { window, limit: button >> 2, slots: [], sent: [rawClick(window, slot, button, mode)] }
        return
      }
      if (!drag) throw new Error(`${name}: a drag click outside a drag`)
      // Its slots and end go to the window it started on, as the server's drag is that window's.
      drag.sent.push(rawClick(drag.window, slot, button, mode))
      if (stage === 1) {
        if (dragTakes(drag, slot)) drag.slots.push(slot)
        return
      }
      const ended = drag
      drag = null
      await Promise.all(ended.sent)
      dragOver(ended)
      return
    }
    if (mode === 6) {
      const done = pickupAll(window, slot, button)
      await rawClick(window, slot, button, mode)
      done()
      return
    }
    if (mode === 1) {
      // Rejected on purpose, the window the server's after it (see actions.js shiftClick).
      await act.shiftClick(window, slot, button)
      return
    }
    await bot.clickWindow(slot, button, mode)
  }

  // The bot's open window for the mod: sent whole once anything in it changed, once per turn.
  let windowQueued = false
  // The properties the server set on the window of that id (a furnace's burn and cook times), by index.
  let properties = { id: null, values: [] }
  // The trades the server listed for the villager window of that id: for the mod (list), and as the
  // items' own class to work out the result slot with (recipes).
  let trades = { id: null, list: [], recipes: [] }
  // A trade list read by hand: the channel's parser (mineflayer's) is built for the client's default
  // version, not the server's, and has no slot type for 1.12. Its fields as the server writes them.
  function readTrades (data) {
    let at = 0
    const i8 = () => { const v = data.readInt8(at); at += 1; return v }
    const i16 = () => { const v = data.readInt16BE(at); at += 2; return v }
    const i32 = () => { const v = data.readInt32BE(at); at += 4; return v }
    function slot () {
      const id = i16()
      if (id === -1) return null
      const count = i8()
      const metadata = i16()
      let tag = null
      if (data[at] === 0) at += 1
      else {
        const parsed = nbt.protos.big.parsePacketBuffer('nbt', data.subarray(at))
        tag = parsed.data
        at += parsed.metadata.size
      }
      if (!bot.registry.items[id]) throw new Error(`${name}: a villager trades item ${id}, not in ${bot.version}`)
      return new (Item())(id, count, metadata, tag)
    }
    const windowId = i32()
    const list = []
    const recipes = []
    for (let n = i8() & 0xff; n > 0; n--) {
      const first = slot()
      const result = slot()
      const second = data[at++] ? slot() : null
      const disabled = data[at++] !== 0
      i32() // uses
      i32() // max uses
      recipes.push({ first, second, result, disabled })
      list.push({ first: itemOut(first), second: itemOut(second), result: itemOut(result), disabled })
    }
    if (at !== data.length) throw new Error(`${name}: a trade list of ${data.length} bytes, read ${at}`)
    return { id: windowId, list, recipes }
  }

  // The villager's result slot. The 1.12 server never sends it: each client works it out from the inputs
  // and the trade picked, and takes the price off the inputs itself as the result is taken (the server
  // does the same without a word), as the game's InventoryMerchant and SlotMerchantResult do. Done here
  // on the window, so the bot sees the result and its click on it is the server's.
  let tradeIndex = 0
  // While the result is set here: any other change of it is the bot's click taking it.
  let settingResult = false
  // The trade the result shown is of, whose price a take pays.
  let shownRecipe = null
  // The game's NBTUtil.areNBTEquals: every tag of the wanted one in the other, lists alike whole.
  const tagsIn = (want, have) => {
    if (want === null || typeof want !== 'object' || Array.isArray(want)) return JSON.stringify(want) === JSON.stringify(have)
    return have !== null && typeof have === 'object' && !Array.isArray(have) && Object.keys(want).every(k => k in have && tagsIn(want[k], have[k]))
  }
  // The game's areItemStacksExactlyEqual: the same item and damage, and the wanted one's tags on it.
  const sameAs = (stack, want) => stack.type === want.type && stack.metadata === want.metadata &&
    (!want.nbt || (Boolean(stack.nbt) && tagsIn(nbt.simplify(want.nbt), nbt.simplify(stack.nbt))))
  const pays = (stack, want) => Boolean(stack) && sameAs(stack, want) && stack.count >= want.count
  // The game's canRecipeBeUsed: the trade picked if the inputs pay it (any other only when the first is picked), else none.
  function usable (a, b) {
    const matches = r => pays(a, r.first) && (r.second ? pays(b, r.second) : !b)
    const recipes = trades.recipes
    if (tradeIndex > 0 && tradeIndex < recipes.length) return matches(recipes[tradeIndex]) ? recipes[tradeIndex] : null
    return recipes.find(matches) ?? null
  }
  // The trade picked, by the arrows or select_trade: the server told, the result worked out again.
  function pickTrade (window, trade) {
    bot._client.writeChannel('MC|TrSel', trade)
    tradeIndex = trade
    merchantResult(window)
  }
  function merchantResult (window) {
    if (trades.id !== window.id) return
    let a = window.slots[0]
    let b = window.slots[1]
    if (!a) {
      a = b
      b = null
    }
    let recipe = a ? usable(a, b) : null
    if ((!recipe || recipe.disabled) && b) recipe = usable(b, a)
    if (recipe?.disabled) recipe = null
    shownRecipe = recipe
    const result = recipe && new (Item())(recipe.result.type, recipe.result.count, recipe.result.metadata, recipe.result.nbt)
    const there = window.slots[2]
    if (!result && !there) return
    if (result && there && Item().equal(result, there) && result.count === there.count) return
    settingResult = true
    window.updateSlot(2, result)
    settingResult = false
  }
  // The game's doTrade: the price off the inputs that pay it, false when they do not.
  function payWith (window, recipe, first, second) {
    const a = window.slots[first]
    const b = window.slots[second]
    if (!pays(a, recipe.first)) return false
    if (recipe.second ? !pays(b, recipe.second) : b) return false
    const shrink = (slot, stack, n) => window.updateSlot(slot, stack.count === n ? null : Object.assign(Object.create(Object.getPrototypeOf(stack)), stack, { count: stack.count - n }))
    shrink(first, a, recipe.first.count)
    if (recipe.second) shrink(second, b, recipe.second.count)
    return true
  }
  function merchant (window) {
    tradeIndex = 0
    shownRecipe = null
    window.on('updateSlot', (slot, oldItem, newItem) => {
      if (slot === 2 && !settingResult && oldItem && (!newItem || newItem.count < oldItem.count)) {
        // Taken: its trade paid off the inputs, each of which works out the result again.
        const recipe = shownRecipe
        if (!recipe) throw new Error(`${name}: the villager's result taken with no trade shown`)
        if (!payWith(window, recipe, 0, 1) && !payWith(window, recipe, 1, 0)) {
          throw new Error(`${name}: the villager's result taken, its inputs ${JSON.stringify([window.slots[0], window.slots[1]].map(itemOut))} do not pay its trade`)
        }
        return
      }
      if (slot === 0 || slot === 1) merchantResult(window)
    })
    merchantResult(window)
  }
  function sendWindow () {
    windowQueued = false
    const window = bot.currentWindow
    const values = window && properties.id === window.id ? properties.values : []
    out(protocol.windowState(name, window && {
      type: act.windowType(window),
      containerSlots: window.inventoryStart,
      cursor: itemOut(window.selectedItem),
      slots: window.slots.map(itemOut),
      properties: Array.from(values, value => value ?? 0),
      trades: trades.id === window.id ? trades.list : []
    }))
  }
  function queueWindow () {
    if (windowQueued) return
    windowQueued = true
    setImmediate(sendWindow)
  }
  bot.on('windowOpen', window => {
    // mineflayer leaves the inventory as it was while a window is open (the server's word on it comes
    // through the window's slots), copied back only as the bot closes it, not when the server does:
    // mirrored as each slot changes, so its hand (heldItem) and the inventory are the server's.
    const offset = window.inventoryStart - bot.inventory.inventoryStart
    const mirror = slot => {
      if (slot < window.inventoryStart || slot >= window.inventoryEnd) return
      const item = window.slots[slot]
      bot._setSlot(slot - offset, item && Object.assign(Object.create(Object.getPrototypeOf(item)), item))
    }
    for (let slot = window.inventoryStart; slot < window.inventoryEnd; slot++) mirror(slot)
    window.on('updateSlot', mirror)
    window.on('updateSlot', queueWindow)
    if (act.windowType(window) === 'minecraft:villager') merchant(window)
    queueWindow()
  })
  bot.on('windowClose', queueWindow)
  // A window's properties may come before mineflayer has it open: kept by its id.
  // The villager's trades: the channel mineflayer's villager plugin registered (by spawn), its parser
  // replaced by the raw bytes (see readTrades).
  bot.once('spawn', () => {
    bot._client.registerChannel('MC|TrList', 'restBuffer')
  })
  bot._client.on('MC|TrList', data => {
    trades = readTrades(data)
    if (bot.currentWindow?.id === trades.id) merchantResult(bot.currentWindow)
    queueWindow()
  })
  bot._client.on('craft_progress_bar', ({ windowId, property, value }) => {
    if (properties.id !== windowId) properties = { id: windowId, values: [] }
    properties.values[property] = value
    queueWindow()
  })

  bot.on('end', reason => {
    act.cancel()
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
        [scope, 'cpu.decode', 'ms', meter.decodeMs],
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

    // A click on its inventory window from the mod (see click above), after the ones before it; the
    // inventory goes back to the mod once it is done, whatever the server made of it.
    click (slot, button, mode, id) {
      // A click the server rejects is fatal, as everything: the rejection says the mod and the bot disagreed.
      clicks = clicks.then(() => click(slot, button, mode)).then(() => {
        lastClick = id
        queueInventory()
        queueWindow()
      })
    },

    // The villager trade its open window shows, picked by the mod's arrows, after the clicks before it.
    selectTrade (trade) {
      clicks = clicks.then(() => {
        if (!bot.currentWindow) throw new Error(`${name}: trade ${trade} picked with no window open`)
        pickTrade(bot.currentWindow, trade)
      })
    },

    // A primitive from the mod (see actions.js).
    action (a) {
      act.run(a)
    },

    // Walks to the spot (the formation's order): the goto primitive.
    goto (spot) {
      act.run({ action: 'goto', spot })
    }
  }
}

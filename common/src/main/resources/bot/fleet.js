// Every mineflayer bot in one process, spread over a pool of worker threads (poolThread.js) that
// share their chunk columns (sharedChunks.js), driven by the mod over one local socket (see protocol.js).
// Their paths are searched on path threads of their own (pathThread.js), which see every column.
// The columns' blocks are not sent: they live in columns.bin, mapped by the threads and the mod.
// Usage: node fleet.js <mod port> <columns.bin>
const net = require('net')
const os = require('os')
const path = require('path')
const { Worker, MessageChannel } = require('worker_threads')
const protocol = require('./protocol')
const states = require('./states')
const { HEADER, SLOTS, loadedHeader } = require('./sharedChunks')

const columnsFile = process.argv[3]
if (!columnsFile) throw new Error('usage: node fleet.js <mod port> <columns.bin>')

// Bot name -> the thread running it.
const bots = new Map()
// Bot name -> where it is ("server|dimension"), from its spawns; unknown until it is in the world.
const worldOf = new Map()
// Formation spots: "server|dimension|x,y,z" -> bot standing there, and bot -> its spot. One bot per block.
const spots = new Map()
const spotOf = new Map()
function leaveSpot (name) {
  const key = spotOf.get(name)
  if (key === undefined) return
  spots.delete(key)
  spotOf.delete(name)
}

// The formations of the latest #formation, one per world its bots are in: "id|world|x,y,z" ->
// { leader, candidates, queue, released }. The spots around the target are worked out once, by
// its first bot in the world (the leader). The leader walks first; the rest wait for its path,
// so each of them finds it in its thread's book and joins it (see communalPaths.js).
const formations = new Map()
let latestFormation = -Infinity
// The bots ordered into the latest formation before they were in the world: bot -> { id, target }.
const waiting = new Map()

function joinFormation (name, { id, target }) {
  const key = `${id}|${worldOf.get(name)}|${target.x},${target.y},${target.z}`
  const formation = formations.get(key)
  if (!formation) {
    formations.set(key, { leader: name, candidates: null, queue: [name], released: false })
    // A spot for every bot of the fleet, if the ground around the target has them.
    bots.get(name).worker.postMessage({ type: 'candidates', bot: name, formation: key, target, needed: bots.size })
  } else if (formation.released) {
    goto(formation, name)
  } else {
    formation.queue.push(name)
  }
}

// The spot nearest the target that no other bot stands on. None left (the ground around the target
// holds fewer bots than the fleet has): the bot stays where it is, and says so.
function goto (formation, name) {
  const thread = bots.get(name)
  if (!thread) return
  const taken = formation.candidates.find(({ key }) => !spots.has(key) || spots.get(key) === name)
  if (!taken) {
    console.log(`[${name}] formation: no free spot (${formation.candidates.length} around the target for ${bots.size} bots)`)
    return
  }
  leaveSpot(name)
  spots.set(taken.key, name)
  spotOf.set(name, taken.key)
  if (name === formation.leader) formation.leaderSpot = taken.spot
  thread.worker.postMessage({ type: 'goto', bot: name, spot: taken.spot })
}

// The rest of the formation walks.
function releaseFormation (formation) {
  formation.released = true
  for (const name of formation.queue.splice(0)) {
    if (name !== formation.leader) goto(formation, name)
  }
}

function formationOrder (name, order) {
  if (order.id < latestFormation) return
  if (order.id > latestFormation) {
    // Every bot is sent to the new one: the spots of the old one are free, or a target near the
    // old one would look taken by bots that have not got their new spot yet.
    latestFormation = order.id
    formations.clear()
    waiting.clear()
    spots.clear()
    spotOf.clear()
  }
  if (worldOf.has(name)) joinFormation(name, order)
  else waiting.set(name, order)
}

// The path threads: every column goes to each of them as it comes and goes, and every path one
// of them finds, to the others. Pool threads ask them over ports of their own (see startThread).
const PATH_THREADS = 3
const pathThreads = []
for (let i = 0; i < PATH_THREADS; i++) {
  const worker = new Worker(path.join(__dirname, 'pathThread.js'), { workerData: { index: i, columnsFile } })
  worker.on('message', message => {
    if (message.dropped !== undefined) {
      settleSlot(message.dropped, worker)
      return
    }
    if (message.wanted !== undefined) {
      wantSnapshots(worker, message.wanted.ticket, message.wanted.world, message.wanted.ids)
      return
    }
    if (!message.path) throw new Error('unknown message from a path thread')
    for (const other of pathThreads) {
      if (other !== worker) other.postMessage({ type: 'path', world: message.path.world, nodes: message.path.nodes })
    }
  })
  worker.on('error', err => { throw err })
  worker.on('exit', code => { throw new Error(`path thread exited (code ${code})`) })
  pathThreads.push(worker)
}
// Requests sent to each path thread and not answered yet, counted by every pool thread (see pathClient.js).
const pathPending = new SharedArrayBuffer(4 * PATH_THREADS)

// The columns in a slot of columns.bin: "server|dimension|cx,cz" -> { header, slot, threads holding
// it, chunk, claim, version, modHolds, ready }. Every path thread sees them all.
//
// Held ones (some thread holds them) are what the mod's registry holds: a column is claimed there
// (CLAIM, chunk being its key there, with its slot) when the fleet's first bot gets it, readied
// (READY) once that bot loaded it into the slot and unloaded (UNLOAD) when its last lets it go, once
// for the whole fleet however many bots hold it. `claim` is this load's id there. The mod snapshots
// it to disk on the UNLOAD and releases the slot (RELEASE); `modHolds` until then.
//
// The rest are cached: let go by every bot, or read off their snapshot by the mod for a search that
// wanted them (see wantSnapshots). They stay for the path threads until slots run short.
const columns = new Map()
let nextClaim = 1
// The cached columns, oldest first: the first to be evicted (see makeRoom).
const cached = new Map()
// Slot -> its column, for every column in `columns`.
const slotColumns = new Map()
// Columns the mod is reading off their snapshot: key -> slot.
const loading = new Map()
// Keys the mod has no snapshot of; forgotten once one is unloaded (its snapshot made then).
const noSnapshot = new Set()
// Held columns changed this turn of the event loop: key -> a bot that saw it. Sent once the turn is
// done (CHANGED), the mod snapshotting each again.
const changed = new Map()

function sendChanged () {
  for (const [key, bot] of changed) send(protocol.changed(bot, columns.get(key).chunk))
  changed.clear()
}

// Slots kept free for the bots: the cache gives way below this, and no snapshot is read into them.
const RESERVE = 256

// The slots of columns.bin no column has, taken from the end. An evicted column's slot stays out of
// it, its blocks untouched, until the mod released it (RELEASE) and every path thread dropped it: a
// path thread takes the drop between searches, so none still reads the slot, and a search through
// a column evicted mid-way never sees another column loaded over it.
const freeSlots = Array.from({ length: SLOTS }, (_, i) => SLOTS - 1 - i)
// Slots evicted -> { mod: waiting on its RELEASE, paths: the path threads yet to drop it }.
const evicting = new Map()

// The mod (worker undefined) or a path thread is done with the evicted slot.
function settleSlot (slot, worker) {
  const pending = evicting.get(slot)
  if (!pending) throw new Error(`slot ${slot} settled, but it was not evicted`)
  if (worker === undefined) {
    if (!pending.mod) throw new Error(`the mod released slot ${slot} twice`)
    pending.mod = false
  } else if (!pending.paths.delete(worker)) {
    throw new Error(`a path thread dropped slot ${slot} twice`)
  }
  if (pending.mod || pending.paths.size > 0) return
  evicting.delete(slot)
  freeSlots.push(slot)
  loadSnapshots()
}

// A cached column out of its slot: the path threads drop it, the slot is free once they all did (see settleSlot).
function evict (key, column) {
  if (column.threads.size > 0) throw new Error(`column ${key} evicted while a thread holds it`)
  cached.delete(key)
  columns.delete(key)
  slotColumns.delete(column.slot)
  evicting.set(column.slot, { mod: column.modHolds, paths: new Set(pathThreads) })
  for (const worker of pathThreads) worker.postMessage({ type: 'drop', key, slot: column.slot })
}

// The oldest cached columns go until the free slots, with those on their way back, are RESERVE again.
function makeRoom () {
  for (const [key, column] of cached) {
    if (freeSlots.length + evicting.size >= RESERVE) return
    evict(key, column)
  }
}

// A slot for a column a bot got.
function takeSlot (key) {
  if (freeSlots.length === 0) throw new Error(`no free slot in columns.bin for ${key}: all ${SLOTS} are held or being evicted`)
  const slot = freeSlots.pop()
  makeRoom()
  return slot
}

// Columns waited on by a path search, to read off their snapshot: key -> the searches waiting on it,
// each { worker, ticket, keys it still waits on }.
const waitedColumns = new Map()
// Columns to read off their snapshot once a slot is to spare, in order.
const toLoad = new Set()

// Columns of `world` ("cx,cz") the path thread's search (ticket) looked for and the fleet does not
// have in memory: read off their snapshot by the mod, the search told once each is in or has none
// (see pathThread.js).
function wantSnapshots (worker, ticket, world, ids) {
  const search = { worker, ticket, keys: new Set() }
  for (const id of ids) {
    const key = `${world}|${id}`
    // In since the search looked (its 'column' is ahead of the answer), or known to have no snapshot.
    if (columns.has(key) || noSnapshot.has(key)) continue
    search.keys.add(key)
    let searches = waitedColumns.get(key)
    if (!searches) waitedColumns.set(key, (searches = new Set()))
    searches.add(search)
    if (!loading.has(key)) toLoad.add(key)
  }
  if (search.keys.size === 0) worker.postMessage({ type: 'wanted', ticket })
  loadSnapshots()
}

// The searches waiting on the column hear it is in, or has no snapshot to read.
function settleWanted (key) {
  const searches = waitedColumns.get(key)
  if (!searches) return
  waitedColumns.delete(key)
  for (const search of searches) {
    search.keys.delete(key)
    if (search.keys.size === 0) search.worker.postMessage({ type: 'wanted', ticket: search.ticket })
  }
}

// Asks the mod for the snapshots to read while slots are to spare past RESERVE. Short of them, cached
// columns give way, their slots coming back once dropped (settleSlot calls this again). With none to
// give way and none coming back, the searches go on without the rest.
function loadSnapshots () {
  for (const key of toLoad) {
    if (columns.has(key)) {
      toLoad.delete(key)
      settleWanted(key)
      continue
    }
    if (freeSlots.length > RESERVE) {
      toLoad.delete(key)
      const slot = freeSlots.pop()
      loading.set(key, slot)
      send(protocol.load(chunkOf(key), slot))
      continue
    }
    if (evicting.size < toLoad.size && cached.size > 0) {
      const [[oldest, column]] = cached
      evict(oldest, column)
      continue
    }
    if (evicting.size > 0 || loading.size > 0) return
    for (const rest of toLoad) settleWanted(rest)
    toLoad.clear()
    return
  }
}

// The mod read the column into the slot (version: its snapshot's Minecraft version), or has no snapshot of it (null).
function onLoaded (key, slot, version) {
  if (loading.get(key) !== slot) throw new Error(`the mod loaded ${key} into slot ${slot}, which it was not asked for`)
  loading.delete(key)
  if (version === null) noSnapshot.add(key)
  // No snapshot, or a bot got the column meanwhile: the slot was never anyone's.
  if (version === null || columns.has(key)) {
    freeSlots.push(slot)
  } else {
    const column = { header: loadedHeader(), slot, threads: new Set(), chunk: chunkOf(key), claim: null, version, modHolds: false, ready: true }
    columns.set(key, column)
    slotColumns.set(slot, column)
    cached.set(key, column)
    for (const worker of pathThreads) worker.postMessage({ type: 'column', key, version, header: column.header, slot })
  }
  settleWanted(key)
  loadSnapshots()
}

// The mod released the slot of an unloaded column: cached, or evicted already.
function onRelease (slot) {
  if (evicting.has(slot)) {
    settleSlot(slot)
    return
  }
  const column = slotColumns.get(slot)
  if (!column || column.threads.size > 0 || !column.modHolds) throw new Error(`the mod released slot ${slot}, which no unloaded column has`)
  column.modHolds = false
}

// "server|dimension|cx,cz" -> the registry's key: { server, dimension, x, z }.
function chunkOf (key) {
  const parts = key.split('|')
  if (parts.length !== 3) throw new Error(`column key ${key} is not server|dimension|cx,cz`)
  const [x, z] = parts[2].split(',').map(Number)
  if (!Number.isInteger(x) || !Number.isInteger(z)) throw new Error(`column key ${key} has no chunk x,z`)
  return { server: parts[0], dimension: parts[1], x, z }
}

// What a pool thread sends, in the order it happened there (see poolThread.js).
function onThreadMessage (thread, message) {
  if (message.acquire !== undefined || message.release !== undefined || message.ready !== undefined || message.changed !== undefined) {
    onChunkRequest(thread, message)
  } else {
    onReport(thread, message)
  }
}

function onChunkRequest (thread, request) {
  if (request.acquire !== undefined) {
    let column = columns.get(request.acquire)
    // Cached (let go before, or off its snapshot): the bot loads it anew, into a slot of its own.
    if (column && column.threads.size === 0) {
      evict(request.acquire, column)
      column = undefined
    }
    const load = !column
    if (load) {
      const slot = takeSlot(request.acquire)
      column = { header: new SharedArrayBuffer(HEADER), slot, threads: new Set(), chunk: chunkOf(request.acquire), claim: nextClaim++, version: request.version, modHolds: true, ready: false }
      columns.set(request.acquire, column)
      slotColumns.set(slot, column)
      for (const worker of pathThreads) worker.postMessage({ type: 'column', key: request.acquire, version: request.version, header: column.header, slot: column.slot })
      send(protocol.claim(request.bot, column.chunk, column.claim, column.slot, request.version))
    }
    column.threads.add(thread)
    // The thread waits on the signal, then takes the answer straight off its port.
    thread.chunkPort.postMessage({ header: column.header, slot: column.slot, load })
    Atomics.store(thread.signal, 0, 1)
    Atomics.notify(thread.signal, 0)
  } else if (request.release !== undefined) {
    const column = columns.get(request.release)
    if (!column || !column.threads.delete(thread)) throw new Error(`a thread released column ${request.release} it does not hold`)
    if (column.threads.size === 0) {
      // Cached: the path threads keep seeing it; the mod snapshots it and releases the slot.
      cached.set(request.release, column)
      // The mod snapshots it once more as it goes: the changes of this turn not sent yet go with it.
      changed.delete(request.release)
      send(protocol.unload(request.bot, column.chunk))
    }
  } else if (request.ready !== undefined) {
    // Its first bot loaded it into the slot: the mod reads it from here on, and snapshots it.
    const column = columns.get(request.ready)
    if (!column || !column.threads.has(thread)) throw new Error(`a thread readied column ${request.ready} it does not hold`)
    column.ready = true
    noSnapshot.delete(request.ready)
    send(protocol.ready(request.bot, column.chunk, column.claim))
  } else if (request.changed !== undefined) {
    const column = columns.get(request.changed)
    if (!column || !column.threads.has(thread)) throw new Error(`a thread changed column ${request.changed} it does not hold`)
    // Readied later: snapshotted then, change and all.
    if (!column.ready) return
    // Once per column and turn, however many bots and threads saw changes in it.
    if (changed.size === 0) setImmediate(sendChanged)
    changed.set(request.changed, request.bot)
  } else {
    throw new Error('unknown chunk request from a pool thread')
  }
}

function onReport (thread, report) {
  if (report.frames) {
    send(Buffer.from(report.frames))
  } else if (report.world) {
    worldOf.set(report.bot, report.world)
    const order = waiting.get(report.bot)
    if (order) {
      waiting.delete(report.bot)
      joinFormation(report.bot, order)
    }
  } else if (report.candidates) {
    // Gone with an older formation: nothing waits on these.
    const formation = formations.get(report.formation)
    if (!formation) return
    formation.candidates = report.candidates
    if (bots.has(formation.leader)) goto(formation, formation.leader)
    else releaseFormation(formation)
  } else if (report.planned !== undefined) {
    // A leader with its path to its spot (or none to find): the rest of its formation walks.
    for (const formation of formations.values()) {
      const spot = formation.leaderSpot
      if (formation.released || formation.leader !== report.bot || !spot || !report.planned) continue
      if (spot.x === report.planned.x && spot.y === report.planned.y && spot.z === report.planned.z) releaseFormation(formation)
    }
  } else if (report.end) {
    leaveSpot(report.bot)
    bots.delete(report.bot)
    worldOf.delete(report.bot)
    waiting.delete(report.bot)
    // A leader gone before its path: the rest of its formation walks without it.
    for (const formation of formations.values()) {
      if (!formation.released && formation.leader === report.bot && formation.candidates) releaseFormation(formation)
    }
    thread.bots--
    if (closing && bots.size === 0) process.exit(0)
  } else {
    throw new Error('unknown report from a pool thread')
  }
}

// The pool: a thread per core left by the path threads at most, each started when a bot needs it
// rather than all at once, so their start (each loads mineflayer and minecraft-data) never takes
// every core together.
const threads = []
const MAX_THREADS = Math.max(1, os.cpus().length - PATH_THREADS)

function startThread () {
  const { port1, port2 } = new MessageChannel()
  const signal = new SharedArrayBuffer(4)
  const thread = { bots: 0, chunkPort: port1, signal: new Int32Array(signal) }
  // A port to each path thread.
  const pathPorts = pathThreads.map(worker => {
    const channel = new MessageChannel()
    worker.postMessage({ type: 'client', port: channel.port2 }, [channel.port2])
    return channel.port1
  })
  thread.worker = new Worker(path.join(__dirname, 'poolThread.js'), {
    workerData: { chunkPort: port2, signal, pathPorts, pathPending, columnsFile },
    transferList: [port2, ...pathPorts]
  })
  thread.worker.on('message', () => { throw new Error('a pool thread wrote to the fleet off its chunk port') })
  thread.worker.on('error', err => { throw err })
  thread.worker.on('exit', code => { throw new Error(`pool thread exited (code ${code})`) })
  port1.on('message', message => onThreadMessage(thread, message))
  threads.push(thread)
  return thread
}

let closing = false

const socket = net.connect(Number(process.argv[2]), '127.0.0.1')
socket.setNoDelay(true)
socket.on('error', err => { throw err })

// Once closing the mod is gone: what the bots still report on their way out has nowhere to go.
const send = frame => { if (!closing) socket.write(frame) }

// First of all: the names of the state ids the slots hold.
send(protocol.stateNames(states.legacyNames()))

socket.on('data', protocol.frames(frame => {
  const message = protocol.decode(frame)
  switch (message.type) {
    case protocol.TYPES.SPAWN: {
      if (bots.has(message.bot)) throw new Error(`a bot named ${message.bot} is already running`)
      // The thread running the fewest bots; a new one while every thread has some and the pool has room.
      const least = threads.reduce((l, t) => !l || t.bots < l.bots ? t : l, null)
      const thread = !least || (least.bots > 0 && threads.length < MAX_THREADS) ? startThread() : least
      thread.bots++
      bots.set(message.bot, thread)
      thread.worker.postMessage({ type: 'spawn', name: message.bot, host: message.host, port: message.port })
      break
    }
    case protocol.TYPES.QUIT:
      // Already gone: its BOT_GONE is on the way to the mod.
      bots.get(message.bot)?.worker.postMessage({ type: 'quit', bot: message.bot })
      break
    case protocol.TYPES.RELEASE:
      // The mod has the snapshot of an unloaded column: done with its slot.
      onRelease(message.slot)
      break
    case protocol.TYPES.LOADED:
      onLoaded(`${message.key.server}|${message.key.dimension}|${message.key.x},${message.key.z}`, message.slot, message.version)
      break
    case protocol.TYPES.FORMATION:
      // Same: a bot already gone has no spot to take. Its old spot goes once it takes the new one.
      if (bots.has(message.bot)) formationOrder(message.bot, { id: message.id, target: { x: message.x, y: message.y, z: message.z } })
      break
  }
}))

// The mod going away (socket or stdin closed) takes every bot with it.
function close () {
  if (closing) return
  closing = true
  if (bots.size === 0) process.exit(0)
  for (const [name, thread] of bots) thread.worker.postMessage({ type: 'quit', bot: name })
}

socket.on('close', close)
process.stdin.on('end', close)
process.stdin.resume()

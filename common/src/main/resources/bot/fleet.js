// Every mineflayer bot in one process, spread over a pool of worker threads (poolThread.js) that
// share their chunk columns (sharedChunks.js), driven by the mod over one local socket (see protocol.js).
// Their paths are searched on path threads of their own (pathThread.js), which see every column.
// Usage: node fleet.js <mod port>
const net = require('net')
const os = require('os')
const path = require('path')
const { Worker, MessageChannel } = require('worker_threads')
const protocol = require('./protocol')
const { SIZE } = require('./sharedChunks')

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
  const worker = new Worker(path.join(__dirname, 'pathThread.js'), { workerData: { index: i } })
  worker.on('message', message => {
    if (!message.path) throw new Error('unknown message from a path thread')
    for (const other of pathThreads) {
      if (other !== worker) other.postMessage({ type: 'path', world: message.path.world, nodes: message.path.nodes })
    }
  })
  worker.on('error', err => { throw err })
  worker.on('exit', code => { throw new Error(`path thread exited (code ${code})`) })
  pathThreads.push(worker)
}

// The shared columns: "server|dimension|cx,cz" -> { buffer, threads holding it }. Gone once no thread does.
const columns = new Map()

function onChunkRequest (thread, request) {
  if (request.acquire !== undefined) {
    let column = columns.get(request.acquire)
    const load = !column
    if (load) {
      columns.set(request.acquire, (column = { buffer: new SharedArrayBuffer(SIZE), threads: new Set() }))
      for (const worker of pathThreads) worker.postMessage({ type: 'column', key: request.acquire, version: request.version, buffer: column.buffer })
    }
    column.threads.add(thread)
    // The thread waits on the signal, then takes the answer straight off its port.
    thread.chunkPort.postMessage({ buffer: column.buffer, load })
    Atomics.store(thread.signal, 0, 1)
    Atomics.notify(thread.signal, 0)
  } else if (request.release !== undefined) {
    const column = columns.get(request.release)
    if (!column || !column.threads.delete(thread)) throw new Error(`a thread released column ${request.release} it does not hold`)
    if (column.threads.size === 0) {
      columns.delete(request.release)
      for (const worker of pathThreads) worker.postMessage({ type: 'drop', key: request.release })
    }
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
    workerData: { chunkPort: port2, signal, pathPorts },
    transferList: [port2, ...pathPorts]
  })
  thread.worker.on('message', report => onReport(thread, report))
  thread.worker.on('error', err => { throw err })
  thread.worker.on('exit', code => { throw new Error(`pool thread exited (code ${code})`) })
  port1.on('message', request => onChunkRequest(thread, request))
  threads.push(thread)
  return thread
}

let closing = false

const socket = net.connect(Number(process.argv[2]), '127.0.0.1')
socket.setNoDelay(true)
socket.on('error', err => { throw err })

// Once closing the mod is gone: what the bots still report on their way out has nowhere to go.
const send = frame => { if (!closing) socket.write(frame) }

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
    case protocol.TYPES.REQUEST_CHUNK:
      // Same: the registry hands the chunk to another holder once BOT_GONE lands.
      bots.get(message.bot)?.worker.postMessage({ type: 'sendChunk', bot: message.bot, key: message.key, claim: message.claim })
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

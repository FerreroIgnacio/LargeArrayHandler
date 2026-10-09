// What the fleet spends, CPU and memory, per thread and per bot: read when the mod asks for a
// profile (PROFILE_REQUEST) or the fleet takes one on an event of its own (a bot in or out), never on a timer. Counters only go up; the mod works out the rates between two
// profiles (see net.mapmcbot.profile.ProfileReport).
//
// A row is [scope, name, unit, value]. Units:
//   ms: time spent so far (CPU, or a handler's), shown as % of a core between two profiles
//   n: how many so far, shown per second
//   bytes: bytes so far, shown per second
//   B: bytes now, #: how many now, %: a percentage now
const { performance, PerformanceObserver } = require('perf_hooks')

// Garbage collections of the calling thread, counted as they happen: { count, ms }.
function watchGc () {
  const gc = { count: 0, ms: 0 }
  new PerformanceObserver(list => {
    for (const entry of list.getEntries()) {
      gc.count++
      gc.ms += entry.duration
    }
  }).observe({ entryTypes: ['gc'] })
  return gc
}

// One per thread: its CPU, event loop and heap when asked for (rows), its garbage collections as they happen.
// A thread's CPU is the time its event loop was busy (event loop utilization): node has no CPU time
// per thread before 22.19 (process.threadCpuUsage). The process's whole CPU is exact (see fleet.js).
function threadProfiler (scope) {
  const gc = watchGc()
  return {
    rows (extra = []) {
      const elu = performance.eventLoopUtilization()
      const memory = process.memoryUsage()
      return [
        [scope, 'cpu', 'ms', elu.active],
        [scope, 'gc.time', 'ms', gc.ms],
        [scope, 'gc.count', 'n', gc.count],
        [scope, 'mem.heapUsed', 'B', memory.heapUsed],
        [scope, 'mem.heapTotal', 'B', memory.heapTotal],
        [scope, 'mem.external', 'B', memory.external],
        [scope, 'mem.arrayBuffers', 'B', memory.arrayBuffers],
        ...extra
      ]
    }
  }
}

// The time a bot's handlers take on its thread: every emit of the emitters given is timed, those
// running inside another of the same bot's counted once (mineflayer emits its events from inside
// the client's packet events).
//
// decodeMs: the time its packets take before any handler (split, decompressed, parsed), the
// handlers they set off along the way left out.
function botMeter () {
  const meter = { ms: 0, events: 0, depth: 0, decodeMs: 0, decodeDepth: 0 }
  // fn timed as a handler, unless inside one already.
  meter.run = function (fn, args = [], self = undefined) {
    if (meter.depth > 0) return fn.apply(self, args)
    meter.depth++
    meter.events++
    const began = performance.now()
    try {
      return fn.apply(self, args)
    } finally {
      meter.depth--
      meter.ms += performance.now() - began
    }
  }
  meter.wrap = emitter => {
    const emit = emitter.emit
    emitter.emit = function (...args) {
      return meter.run(emit, args, this)
    }
  }
  // A stream of the client's way in: its transforms timed as decoding, once however they nest.
  meter.wrapDecode = stream => {
    const transform = stream._transform
    stream._transform = function (...args) {
      if (meter.decodeDepth > 0) return transform.apply(this, args)
      meter.decodeDepth++
      const handlers = meter.ms
      const began = performance.now()
      try {
        return transform.apply(this, args)
      } finally {
        meter.decodeDepth--
        meter.decodeMs += performance.now() - began - (meter.ms - handlers)
      }
    }
  }
  return meter
}

// The rows as the PROFILE frame carries them: one line each, tab-separated.
function toText (rows) {
  return rows.map(([scope, name, unit, value]) => {
    if (/[\t\n]/.test(scope + name)) throw new Error(`profile row with a tab or a newline in it: ${scope} ${name}`)
    if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`profile row ${scope} ${name} is not a number: ${value}`)
    return `${scope}\t${name}\t${unit}\t${Number(value.toFixed(3))}`
  }).join('\n')
}

module.exports = { threadProfiler, botMeter, toText }

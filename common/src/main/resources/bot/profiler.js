// What the fleet spends, CPU and memory, per thread and per bot: read when the mod asks for a
// profile (PROFILE_REQUEST) or the fleet takes one on an event of its own (a bot in or out, a new
// formation), never on a timer. Counters only go up; the mod works out the rates between two
// profiles (see net.mapmcbot.profile.ProfileReport).
//
// A row is [scope, name, unit, value]. Units:
//   ms: time spent so far (CPU, or a handler's), shown as % of a core between two profiles
//   n: how many so far, shown per second
//   bytes: bytes so far, shown per second
//   B: bytes now, #: how many now, %: a percentage now
const { performance, PerformanceObserver } = require('perf_hooks')

if (typeof process.threadCpuUsage !== 'function') {
  throw new Error(`node ${process.version} has no process.threadCpuUsage: the profiler needs node 22.19 or later`)
}

// CPU time of the calling thread so far, user and system, in ms.
function threadCpu () {
  const { user, system } = process.threadCpuUsage()
  return { user: user / 1000, system: system / 1000 }
}

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
function threadProfiler (scope) {
  const gc = watchGc()
  return {
    rows (extra = []) {
      const cpu = threadCpu()
      const elu = performance.eventLoopUtilization()
      const memory = process.memoryUsage()
      return [
        [scope, 'cpu.user', 'ms', cpu.user],
        [scope, 'cpu.system', 'ms', cpu.system],
        [scope, 'loop.active', 'ms', elu.active],
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
function botMeter () {
  const meter = { ms: 0, events: 0, depth: 0 }
  meter.wrap = emitter => {
    const emit = emitter.emit
    emitter.emit = function (...args) {
      if (meter.depth > 0) return emit.apply(this, args)
      meter.depth++
      meter.events++
      const began = performance.now()
      try {
        return emit.apply(this, args)
      } finally {
        meter.depth--
        meter.ms += performance.now() - began
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

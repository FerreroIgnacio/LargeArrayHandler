// mineflayer-pathfinder's getPathTo for the bots of a pool thread: their searches run on the
// fleet's path threads (see pathThread.js), never here. getPathTo sends the request and hands
// pathfinder no path yet, so the bot stands; once the answer comes the bot sets its goal again,
// pathfinder asks again, and getPathTo hands it the path. Asked again for the goal it is already
// walking to (pathfinder drops its path when a block near it changes, when the bot gets stuck or
// fails to dig), the bot keeps walking what is left of its path until the new one comes. A goal no
// search found a path to is not searched again until the bot is RETRY_AFTER blocks from where it was.
// Each request goes to the path thread with the fewest waiting, counted across the whole fleet.
const RETRY_AFTER = 4
// Entities go with a request only inside the box of its start and goal widened by this many blocks
// (half communalPaths.js's SEARCH_RADIUS, as far as a search strays to either side), and only
// those pathfinder's Movements counts: a bot in a crowd sees every other bot of the fleet.
const ENTITY_MARGIN = 32
const { Vec3 } = require('vec3')
const Move = require('mineflayer-pathfinder/lib/move')
const { GoalBlock, GoalNearXZ } = require('mineflayer-pathfinder/lib/goals')

// `ports`: one per path thread, from the fleet. `pending`: per path thread, the requests sent to it
// and not answered yet, shared by every pool thread: one more as a request goes, one less as its
// answer (or its cancel) comes, every request getting exactly one.
function pathClient (ports, pending) {
  if (pending.length !== ports.length) throw new Error(`${pending.length} pending counts for ${ports.length} path threads`)
  // Request id -> what to do with its answer.
  const waiting = new Map()
  let nextId = 1
  // Where the search for the least busy starts, turning, so ties do not all land on the first.
  let first = 0
  ports.forEach((port, i) => {
    port.on('message', answer => {
      const onAnswer = waiting.get(answer.id)
      if (!onAnswer) throw new Error(`a path thread answered request ${answer.id}, which no bot is waiting on`)
      waiting.delete(answer.id)
      if (Atomics.sub(pending, i, 1) <= 0) throw new Error(`path thread ${i} answered more requests than it was sent`)
      onAnswer(answer)
    })
  })

  // The path thread with the fewest requests waiting, counted as one more.
  function leastBusy () {
    let best = -1
    let fewest = Infinity
    for (let k = 0; k < ports.length; k++) {
      const i = (first + k) % ports.length
      const count = Atomics.load(pending, i)
      if (count < fewest) {
        fewest = count
        best = i
      }
    }
    first = (first + 1) % ports.length
    Atomics.add(pending, best, 1)
    return ports[best]
  }

  // getPathTo(movements, goal) for the bot; `world()` is its "server|dimension" as the shared
  // columns are keyed, `onPlanned(goal)` gets the goal ({x, y, z}, null without a position) of
  // each search answered, found or not.
  function planner (bot, world, onPlanned) {
    const water = bot.registry.blocksByName.water.id
    const ladder = bot.registry.blocksByName.ladder.id
    const vine = bot.registry.blocksByName.vine.id

    // The request on its way, the answer done and not handed over yet, and the path handed over
    // last: { goal, ... }. pathfinder walks that path's own array, taking each node off as it gets
    // there: what is in it is what is left.
    let running = null
    let done = null
    let walking = null
    // The last search that found no path: { goal, start, result }.
    let failed = null
    bot.on('end', () => cancel())

    function cancel () {
      if (!running) return
      running.cancelled = true
      running.port.postMessage({ cancel: running.id })
      running = null
    }

    // The block the bot stands in, as pathfinder's getPathFromTo takes it.
    function start (movements) {
      const position = bot.entity.position
      const p = position.floored()
      const block = bot.blockAt(p)
      const offset = block && position.y - p.y > 0.001 && bot.entity.onGround && !movements.emptyBlocks.has(block.type) ? 1 : 0
      return { x: p.x, y: p.y + offset, z: p.z, remainingBlocks: movements.countScaffoldingItems() }
    }

    function goalOf (goal) {
      if (goal instanceof GoalBlock) return { kind: 'block', x: goal.x, y: goal.y, z: goal.z }
      if (goal instanceof GoalNearXZ) return { kind: 'nearXZ', x: goal.x, z: goal.z, range: Math.sqrt(goal.rangeSq) }
      throw new Error(`${bot.username}: the path threads know GoalBlock and GoalNearXZ, not ${goal.constructor.name}`)
    }

    // pathfinder's postProcessPath without the shortcuts (off by default there): each node up to
    // the first that digs or places moved to where the bot stands on that block, as its movement
    // loop expects.
    function onTopOf (block) {
      if (!block || block.shapes.length === 0) return null
      const p = new Vec3(0.5, 0, 0.5)
      let n = 1
      for (const shape of block.shapes) {
        const h = shape[4]
        if (h === p.y) {
          p.x += (shape[0] + shape[3]) / 2
          p.z += (shape[2] + shape[5]) / 2
          n++
        } else if (h > p.y) {
          n = 2
          p.x = 0.5 + (shape[0] + shape[3]) / 2
          p.y = h
          p.z = 0.5 + (shape[2] + shape[5]) / 2
        }
      }
      p.x /= n
      p.z /= n
      return block.position.plus(p)
    }

    function standOn (path) {
      for (let i = 0; i < path.length; i++) {
        const node = path[i]
        if (node.toBreak.length > 0 || node.toPlace.length > 0) break
        const block = bot.blockAt(new Vec3(node.x, node.y, node.z))
        // In a column the server has not sent this bot yet (the path threads see the whole fleet's):
        // the middle of the block, at its height, standing on a whole block.
        if (!block) {
          node.x = Math.floor(node.x) + 0.5
          node.z = Math.floor(node.z) + 0.5
          continue
        }
        if (block && (block.type === water || ((block.type === ladder || block.type === vine) && i + 1 < path.length && path[i + 1].y < node.y))) {
          node.x = Math.floor(node.x) + 0.5
          node.y = Math.floor(node.y)
          node.z = Math.floor(node.z) + 0.5
          continue
        }
        const top = onTopOf(block) ?? onTopOf(bot.blockAt(new Vec3(node.x, node.y - 1, node.z)))
        if (top) {
          node.x = top.x
          node.y = top.y
          node.z = top.z
        } else {
          node.x = Math.floor(node.x) + 0.5
          node.y = node.y - 1
          node.z = Math.floor(node.z) + 0.5
        }
      }
      return path
    }

    // The answer's moves as pathfinder's own: Moves (Vec3s), with their blocks to place.
    function movesOf (moves) {
      return moves.map(m => new Move(m.x, m.y, m.z, m.remainingBlocks, m.cost, m.toBreak,
        m.toPlace.map(p => p.returnPos ? { ...p, returnPos: new Vec3(p.returnPos.x, p.returnPos.y, p.returnPos.z) } : p), m.parkour))
    }

    return function getPathTo (movements, goal) {
      if (done && done.goal === goal) {
        const result = done.result
        done = null
        walking = { goal, path: result.path }
        return result
      }
      if (!running || running.goal !== goal) {
        // Given up on from about here already: the same answer, without asking again.
        const from = start(movements)
        if (failed && failed.goal === goal && Math.abs(from.x - failed.start.x) + Math.abs(from.y - failed.start.y) + Math.abs(from.z - failed.start.z) < RETRY_AFTER) {
          return { ...failed.result, path: [] }
        }
        cancel()
        const job = { goal, id: nextId++, port: leastBusy(), cancelled: false, began: performance.now(), start: from }
        const self = bot.entity
        waiting.set(job.id, answer => {
          if (job.cancelled) return
          if (answer.cancelled) throw new Error(`${bot.username}: request ${job.id} cancelled without being asked to`)
          running = null
          const path = movesOf(answer.moves)
          failed = answer.status !== 'success'
            ? { goal, start: job.start, result: { status: answer.status, reason: answer.reason, cost: 0, time: 0, visitedNodes: 0, generatedNodes: 0 } }
            : null
          done = {
            goal,
            result: {
              status: answer.status,
              reason: answer.reason,
              cost: path.reduce((sum, node) => sum + node.cost, 0),
              time: performance.now() - job.began,
              visitedNodes: answer.visitedNodes,
              generatedNodes: answer.generatedNodes,
              path: standOn(path)
            }
          }
          onPlanned(goal.x !== undefined && goal.y !== undefined ? { x: goal.x, y: goal.y, z: goal.z } : null)
          // Still the goal: pathfinder asks again, and gets it.
          if (bot.pathfinder.goal === goal) bot.pathfinder.setGoal(goal)
        })
        const target = goalOf(goal)
        const minX = Math.min(from.x, target.x) - ENTITY_MARGIN
        const maxX = Math.max(from.x, target.x) + ENTITY_MARGIN
        const minZ = Math.min(from.z, target.z) - ENTITY_MARGIN
        const maxZ = Math.max(from.z, target.z) + ENTITY_MARGIN
        const entities = []
        for (const id in bot.entities) {
          const e = bot.entities[id]
          if (e === self || !e.position) continue
          if (movements.passableEntities.has(e.name) && !movements.entitiesToAvoid.has(e.name)) continue
          const { x, y, z } = e.position
          if (x < minX || x > maxX || z < minZ || z > maxZ) continue
          entities.push({ name: e.name, width: e.width, height: e.height, position: { x, y, z } })
        }
        job.port.postMessage({
          id: job.id,
          world: world(),
          version: bot.version,
          start: from,
          goal: target,
          items: bot.inventory.items().map(item => ({ type: item.type, nbt: item.nbt })),
          effects: self.effects,
          entities
        })
        running = job
      }
      // No new path yet: the rest of the one it was walking to the same goal, else it stands.
      const rest = walking && walking.goal === goal ? walking.path : []
      return { status: 'partial', cost: 0, time: 0, visitedNodes: 0, generatedNodes: 0, path: rest }
    }
  }

  return { planner }
}

module.exports = { pathClient }

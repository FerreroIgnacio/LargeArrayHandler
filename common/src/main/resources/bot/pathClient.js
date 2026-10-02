// mineflayer-pathfinder's getPathFromTo for the bots of a pool thread: their searches run on the
// fleet's path threads (see pathThread.js), never here. A bot with a goal walks what the search has
// found so far: each time the search gets nearer to the goal the thread sends the path to there (a
// 'partial' answer), and the bot takes it from pathfinder's own astar context, whose compute() hands
// out the latest (pathfinder calls it each tick while the status is 'partial'). The search ends with
// 'success' or 'noPath': the answer that says so is the last. A bot that walked a partial path to its
// end stands, waiting for the next, and says so (`waiting` of the results, see bot.js).
// Asked again for the goal it is already searching, the bot goes on with that search (pathfinder drops
// its path when a block near it changes, when the bot gets stuck or fails to dig). A goal no search
// found a path to is not searched again until the bot is RETRY_AFTER blocks from where it was.
// Each request goes to the path thread with the fewest waiting, counted across the whole fleet.
const RETRY_AFTER = 4
// Entities go with a request only inside the box of its start and goal widened by this many blocks
// (a window around the search, which is no longer bounded by a radius), and only
// those pathfinder's Movements counts: a bot in a crowd sees every other bot of the fleet.
const ENTITY_MARGIN = 32
const { Vec3 } = require('vec3')
const Move = require('mineflayer-pathfinder/lib/move')
const { GoalBlock } = require('mineflayer-pathfinder/lib/goals')

// `ports`: one per path thread, from the fleet. `pending`: per path thread, the requests sent to it
// and not ended yet, shared by every pool thread: one more as a request goes, one less as its last
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
      // A partial answer is not the last: the request goes on.
      if (answer.status !== 'partial') {
        waiting.delete(answer.id)
        if (Atomics.sub(pending, i, 1) <= 0) throw new Error(`path thread ${i} answered more requests than it was sent`)
      }
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
  // columns are keyed.
  function planner (bot, world) {
    const water = bot.registry.blocksByName.water.id
    const ladder = bot.registry.blocksByName.ladder.id
    const vine = bot.registry.blocksByName.vine.id

    // The search of the goal the bot walks to: { goal, id, port, cancelled, began, start, answer, final,
    // delivered }. `answer`: the latest of the thread, { status, reason, moves, ... }; `final`: it is the last.
    let job = null
    // The path handed to pathfinder last, and the goal it was for: pathfinder walks that path's own
    // array, taking each node off as it gets there: what is in it is what is left.
    let held = []
    let heldGoal = null
    // The last search that found no path: { goal, start, result }.
    let failed = null
    // Changes with each result that tells the mod something new (see bot.js).
    let sequence = 0
    bot.on('end', () => cancel())

    function cancel () {
      if (!job || job.final) return
      job.cancelled = true
      job.port.postMessage({ cancel: job.id })
      job = null
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
      throw new Error(`${bot.username}: the path threads know GoalBlock, not ${goal.constructor.name}`)
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

    // The node reached: where the bot stands, as pathfinder takes a node as arrived at.
    function reached (node) {
      const p = bot.entity.position
      return Math.abs(node.x - p.x) <= 0.35 && Math.abs(node.z - p.z) <= 0.35 && Math.abs(node.y - p.y) < 1
    }

    // The same move as another Move, sharing its blocks to break and place (what is dug or placed on
    // one is dug or placed on all), standing where standOn put it.
    function copy (m) {
      const c = new Move(m.x, m.y, m.z, m.remainingBlocks, m.cost, m.toBreak, m.toPlace, m.parkour)
      c.x = m.x
      c.y = m.y
      c.z = m.z
      return c
    }

    // The result for pathfinder of the latest answer of the job: what pathfinder's compute would give.
    // A path of its own each time, as compute's are: pathfinder cuts it down to where the bot is.
    function resultOf (job) {
      const answer = job.answer
      let path
      let waiting
      if (!answer) {
        // Nothing found yet: the rest of the path it was walking to this goal, else it stands.
        path = heldGoal === job.goal ? held : []
        waiting = path.length === 0 || reached(path[path.length - 1])
      } else {
        path = answer.moves.map(copy)
        waiting = !job.final && (path.length === 0 || reached(path[path.length - 1]))
        if (job.final) job.delivered = true
      }
      held = path
      heldGoal = job.goal
      return {
        status: answer ? answer.status : 'partial',
        reason: answer?.reason,
        cost: path.reduce((sum, node) => sum + node.cost, 0),
        time: performance.now() - job.began,
        visitedNodes: answer ? answer.visitedNodes : 0,
        generatedNodes: answer ? answer.generatedNodes : 0,
        path,
        waiting,
        // Told by the answer it is of and by waiting: the same one asked again is nothing new.
        token: `${job.id}:${answer ? answer.sequence : 0}:${waiting}`
      }
    }

    // pathfinder's astar context for the job: it calls compute() on each tick while the status it
    // got is 'partial'. Nothing it visited is kept here: no chunk loading cuts the path.
    function contextOf (job) {
      return { visitedChunks: new Set(), compute: () => resultOf(job) }
    }

    function begin (movements, goal, from) {
      cancel()
      job = { goal, id: nextId++, port: leastBusy(), cancelled: false, final: false, delivered: false, answer: null, began: performance.now(), start: from }
      const mine = job
      const self = bot.entity
      waiting.set(mine.id, answer => {
        if (mine.cancelled) return
        if (answer.cancelled) throw new Error(`${bot.username}: request ${mine.id} cancelled without being asked to`)
        mine.answer = {
          status: answer.status,
          reason: answer.reason,
          visitedNodes: answer.visitedNodes,
          generatedNodes: answer.generatedNodes,
          moves: standOn(movesOf(answer.moves)),
          sequence: ++sequence
        }
        if (answer.status === 'partial') return
        mine.final = true
        if (answer.status === 'noPath') {
          failed = { goal, start: mine.start, result: { status: 'noPath', reason: answer.reason, cost: 0, time: 0, visitedNodes: 0, generatedNodes: 0 } }
        } else {
          failed = null
        }
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
      mine.port.postMessage({
        id: mine.id,
        bot: bot.username,
        world: world(),
        version: bot.version,
        start: from,
        goal: target,
        items: bot.inventory.items().map(item => ({ type: item.type, nbt: item.nbt })),
        effects: self.effects,
        entities
      })
      return mine
    }

    // pathfinder's getPathTo calls this and takes the first value: the result to start walking, and the
    // context that gives it the next ones.
    bot.pathfinder.getPathFromTo = function * getPathFromTo (movements, startPos, goal) {
      // The search of this goal still going, or its last answer not handed over yet: that one.
      if (job && job.goal === goal && (!job.final || !job.delivered)) {
        yield { result: resultOf(job), astarContext: contextOf(job) }
        return
      }
      // Given up on from about here already: the same answer, without asking again.
      const from = start(movements)
      if (failed && failed.goal === goal && Math.abs(from.x - failed.start.x) + Math.abs(from.y - failed.start.y) + Math.abs(from.z - failed.start.z) < RETRY_AFTER) {
        held = []
        heldGoal = goal
        yield { result: { ...failed.result, path: [], waiting: false, token: `failed:${++sequence}` }, astarContext: null }
        return
      }
      const mine = begin(movements, goal, from)
      yield { result: resultOf(mine), astarContext: contextOf(mine) }
    }
  }

  return { planner }
}

module.exports = { pathClient }

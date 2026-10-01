// Paths found by the fleet's bots, shared: two bots close together heading about the same place
// would find about the same path, so the second joins the first's instead of searching the whole
// way. A path joins when it passes near the bot (JOIN_NEAR) and ends near its goal (NEAR_GOAL):
// the bot searches only the few blocks to it, follows it while each move still holds in its own
// world, and searches the rest. Every path found goes into the book of its pool thread
// and, through the fleet, into the book of every other thread (see fleet.js).
//
// Replaces mineflayer-pathfinder's getPathTo, which its movement loop calls whenever it needs a
// path: the search runs to the end in one go, bounded by distance (SEARCH_RADIUS) and by how many
// nodes it expands (MAX_EXPANDED), never by time. A search that goes on blocks every bot of its
// thread, keepalives included.
const AStar = require('mineflayer-pathfinder/lib/astar')
const Move = require('mineflayer-pathfinder/lib/move')
const { Vec3 } = require('vec3')

// Paths kept per world; the oldest goes when one more comes.
const KEPT = 256
// A path joins at its nodes this close to the bot (blocks, each axis)...
const JOIN_NEAR = 6
// ...when it gets this close to the goal (the goal's heuristic, about blocks).
const NEAR_GOAL = 8
// How much costlier than the straight distance a search may get (pathfinder's searchRadius).
const SEARCH_RADIUS = 64
// Nodes a search expands at most: past it the search gives up with the nearest it got (noPath).
const MAX_EXPANDED = 4000

// The paths of one pool thread, per world ("server|dimension"): each an Int32Array x, y, z per node.
function pathBook () {
  const worlds = new Map()

  return {
    add (world, nodes) {
      if (nodes.length % 3 !== 0) throw new Error(`a path of ${nodes.length} coordinates is not whole nodes`)
      if (nodes.length < 6) return
      let paths = worlds.get(world)
      if (!paths) worlds.set(world, (paths = []))
      paths.push(nodes)
      if (paths.length > KEPT) paths.shift()
    },

    // The nodes near `start` worth joining on the way to `goal` (a pathfinder goal with a position):
    // node hash -> { nodes, from, to, left }, the path to follow from node `from` to node `to`, its
    // nearest to the goal, `left` from there.
    joinsToward (world, start, goal) {
      const joins = new Map()
      const point = { x: 0, y: 0, z: 0 }
      for (const nodes of worlds.get(world) ?? []) {
        const count = nodes.length / 3
        let to = 0
        let left = Infinity
        for (let n = 0; n < count; n++) {
          point.x = nodes[n * 3]
          point.y = nodes[n * 3 + 1]
          point.z = nodes[n * 3 + 2]
          const h = goal.heuristic(point)
          if (h < left) {
            left = h
            to = n
          }
        }
        if (left > NEAR_GOAL) continue
        for (let from = 0; from < to; from++) {
          const x = nodes[from * 3]
          const y = nodes[from * 3 + 1]
          const z = nodes[from * 3 + 2]
          if (Math.abs(x - start.x) > JOIN_NEAR || Math.abs(y - start.y) > JOIN_NEAR || Math.abs(z - start.z) > JOIN_NEAR) continue
          const hash = `${x},${y},${z}`
          const known = joins.get(hash)
          // The one that leaves the least to walk.
          if (!known || to - from + left < known.to - known.from + known.left) joins.set(hash, { nodes, from, to, left })
        }
      }
      return joins
    }
  }
}

// The moves from `start` along a path of the book, from node `from` + 1 to `to`, each one checked
// against the bot's world as the next step from the one before; stops at the first that no longer holds.
function follow (movements, start, { nodes, from, to }) {
  const moves = []
  let current = start
  for (let n = from + 1; n <= to; n++) {
    const x = nodes[n * 3]
    const y = nodes[n * 3 + 1]
    const z = nodes[n * 3 + 2]
    const next = movements.getNeighbors(current).find(m => m.x === x && m.y === y && m.z === z)
    if (!next) break
    moves.push(next)
    current = next
  }
  return moves
}

// A* from start to goal, giving up once it expanded MAX_EXPANDED nodes: past that every node is a
// dead end, so the open set drains at once. `exhausted` tells that apart from a real noPath.
function search (start, movements, goal) {
  let expanded = 0
  const budgeted = Object.create(movements)
  budgeted.getNeighbors = node => ++expanded > MAX_EXPANDED ? [] : movements.getNeighbors(node)
  const result = new AStar(start, budgeted, goal, Infinity, Infinity, SEARCH_RADIUS).compute()
  result.exhausted = expanded > MAX_EXPANDED
  return result
}

// getPathTo(movements, goal) for the bot, drawing on `book` for the bot's world `world()`;
// `onPath(goal, nodes)` gets every path found (nodes as for the book, start included; null when
// none was found), for the fleet to hand to the other threads.
function communalPlanner (bot, book, world, onPath) {
  const water = bot.registry.blocksByName.water.id
  const ladder = bot.registry.blocksByName.ladder.id
  const vine = bot.registry.blocksByName.vine.id

  // The block the bot stands in, as pathfinder's getPathFromTo takes it.
  function startMove (movements) {
    const position = bot.entity.position
    const p = position.floored()
    const block = bot.blockAt(p)
    const offset = block && position.y - p.y > 0.001 && bot.entity.onGround && !movements.emptyBlocks.has(block.type) ? 1 : 0
    return new Move(p.x, p.y + offset, p.z, movements.countScaffoldingItems(), 0)
  }

  // pathfinder's postProcessPath without the shortcuts (off by default there): each node up to the
  // first that digs or places moved to where the bot stands on that block, as its movement loop expects.
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

  return function getPathTo (movements, goal) {
    const began = performance.now()
    const start = startMove(movements)
    if (movements.allowEntityDetection) {
      movements.clearCollisionIndex()
      movements.updateCollisionIndex()
    }

    // Goals with a position (GoalBlock, GoalNear...) can join a path; any other searches it all.
    const positioned = goal.x !== undefined && goal.y !== undefined && goal.z !== undefined
    // Nothing to walk on there: no search would find it, after searching everything it may.
    if (positioned && !bot.blockAt(new Vec3(goal.x, goal.y, goal.z))) {
      onPath({ x: goal.x, y: goal.y, z: goal.z }, null)
      return { status: 'noPath', reason: 'target not loaded', cost: 0, time: performance.now() - began, visitedNodes: 0, generatedNodes: 0, path: [] }
    }
    const joins = positioned ? book.joinsToward(world(), start, goal) : new Map()
    // A node to join counts as nothing left to go, so the search takes the nearest one first.
    const toward = joins.size === 0
      ? goal
      : { heuristic: node => joins.has(node.hash) ? 0 : goal.heuristic(node), isEnd: node => goal.isEnd(node) || joins.has(node.hash) }

    const first = search(start, movements, toward)
    const path = first.path
    let status = first.status
    let exhausted = first.exhausted
    let visitedNodes = first.visitedNodes
    let generatedNodes = first.generatedNodes
    const reached = path.length > 0 ? path[path.length - 1] : start
    if (status === 'success' && !goal.isEnd(reached)) {
      // Joined a path: along it, then the rest of the way.
      const along = follow(movements, reached, joins.get(reached.hash))
      path.push(...along)
      const from = along.length > 0 ? along[along.length - 1] : reached
      if (!goal.isEnd(from)) {
        const rest = search(from, movements, goal)
        path.push(...rest.path)
        status = rest.status
        exhausted = rest.exhausted
        visitedNodes += rest.visitedNodes
        generatedNodes += rest.generatedNodes
      }
    }

    let nodes = null
    if (status === 'success') {
      nodes = new Int32Array((path.length + 1) * 3)
      ;[start, ...path].forEach((node, i) => nodes.set([node.x, node.y, node.z], i * 3))
      book.add(world(), nodes)
    }
    onPath(positioned ? { x: goal.x, y: goal.y, z: goal.z } : null, nodes)

    return {
      status,
      reason: status !== 'success' && exhausted ? `gave up after ${MAX_EXPANDED} nodes` : undefined,
      cost: path.reduce((sum, node) => sum + node.cost, 0),
      time: performance.now() - began,
      visitedNodes,
      generatedNodes,
      path: standOn(path)
    }
  }
}

module.exports = { pathBook, communalPlanner }

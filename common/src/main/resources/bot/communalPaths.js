// Path search for the fleet's path threads (see pathThread.js), and the paths found, shared: two
// bots close together heading about the same place would find about the same path, so the
// second joins the first's instead of searching the whole way. A path joins when it passes near
// the bot (JOIN_NEAR) and ends near its goal (NEAR_GOAL): the search covers only the few blocks to
// it, follows it while each move still holds in the world, and searches the rest. A path found
// broken as it is followed is cut where it breaks, and goes on the way the rest was found, for
// every path thread (see plan).
//
// Bounded by time as in mineflayer-pathfinder: thinkTimeout for the whole search, tickTimeout per compute() slice.
const AStar = require('mineflayer-pathfinder/lib/astar')

// Paths kept per world; the oldest goes when one more comes.
const KEPT = 256
// A path joins at its nodes this close to the bot (blocks, each axis)...
const JOIN_NEAR = 12
// ...when it gets this close to the goal (the goal's heuristic, about blocks).
const NEAR_GOAL = 8

// The paths of the fleet as one path thread has them, per world ("server|dimension"): id -> an
// Int32Array x, y, z per node, oldest first. Each path has the id its path thread gave it
// ("thread:n"): the paths this thread finds or cuts go to the rest with `share(world, id, nodes)`
// (through the fleet, see pathThread.js), theirs come in with `apply`. Two threads cutting the same
// path at once may end up each with the other's cut: each one a path that held when it was cut.
//
// Possible optimization, for later: a path is checked only as it is followed, so one that still
// holds but is no longer the best way (a bridge built, a wall dug through since) keeps being joined.
// Dropping the paths through a column when one of its blocks changes (the fleet hears of it, see
// bot.js's markChanged) would leave the search to find the better way.
function pathBook (thread, share) {
  const worlds = new Map()
  let next = 0

  // Too short to join (fewer than two nodes): gone. Not here (gone as the oldest, or never in):
  // in as the newest.
  function put (world, id, nodes) {
    if (nodes.length % 3 !== 0) throw new Error(`a path of ${nodes.length} coordinates is not whole nodes`)
    let paths = worlds.get(world)
    if (!paths) worlds.set(world, (paths = new Map()))
    if (nodes.length < 6) {
      paths.delete(id)
      return
    }
    paths.set(id, nodes)
    if (paths.size > KEPT) paths.delete(paths.keys().next().value)
  }

  return {
    // A path this thread found.
    add (world, nodes) {
      if (nodes.length % 3 !== 0) throw new Error(`a path of ${nodes.length} coordinates is not whole nodes`)
      if (nodes.length < 6) return
      const id = `${thread}:${next++}`
      put(world, id, nodes)
      share(world, id, nodes)
    },

    // A path of the book as this thread found it now: broken on from some node, its nodes up to it
    // (and the way on, if found).
    cut (world, id, nodes) {
      put(world, id, nodes)
      share(world, id, nodes)
    },

    // A path another thread found or cut.
    apply: put,

    // The nodes near `start` worth joining on the way to `goal` (a pathfinder goal with a position):
    // node hash -> { id, nodes, from, to, left }, the path to follow from node `from` to node `to`,
    // its nearest to the goal, `left` from there.
    joinsToward (world, start, goal) {
      const joins = new Map()
      const point = { x: 0, y: 0, z: 0 }
      for (const [id, nodes] of worlds.get(world) ?? []) {
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
          if (!known || to - from + left < known.to - known.from + known.left) joins.set(hash, { id, nodes, from, to, left })
        }
      }
      return joins
    }
  }
}

// The moves from `start` along a path of the book, from node `from` + 1 to `to`, each one checked
// against the world as the next step from the one before; stops at the first that no longer holds.
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

// A* from start to goal as pathfinder's getPathFromTo runs it: compute() slices of `tickTimeout` ms
// ('partial') continue until the search ends or `thinkTimeout` ms in all have passed ('timeout', the best
// path so far). No search radius.
function search (start, movements, goal, thinkTimeout, tickTimeout) {
  let result = new AStar(start, movements, goal, thinkTimeout, tickTimeout, -1).compute()
  while (result.status === 'partial') result = result.context.compute()
  return result
}

// `nodes` (an Int32Array x, y, z per node) with the nodes of the Moves `moves` after them.
function withMoves (nodes, moves) {
  const all = new Int32Array(nodes.length + moves.length * 3)
  all.set(nodes)
  moves.forEach((m, i) => all.set([m.x, m.y, m.z], nodes.length + i * 3))
  return all
}

// The path from the Move `start` to `goal` in `world`, joining a path of `book` when one fits;
// what was found goes into the book. A joined path that breaks as it is followed is cut in the book
// at the last node that holds, and goes on the way the rest of the search found to the goal, if it
// did (what it had past where it broke is gone: past the node nearest the goal no search checked it).
// { status, visitedNodes, generatedNodes, path: [Move] }.
function plan (book, world, start, movements, goal, thinkTimeout, tickTimeout) {
  // Goals with a position (GoalBlock, GoalNear...) can join a path; any other searches it all.
  const positioned = goal.x !== undefined && goal.y !== undefined && goal.z !== undefined
  const joins = positioned ? book.joinsToward(world, start, goal) : new Map()
  // A node to join counts as nothing left to go, so the search takes the nearest one first.
  const toward = joins.size === 0
    ? goal
    : { heuristic: node => joins.has(node.hash) ? 0 : goal.heuristic(node), isEnd: node => goal.isEnd(node) || joins.has(node.hash) }

  const first = search(start, movements, toward, thinkTimeout, tickTimeout)
  const path = first.path
  let status = first.status
  let visitedNodes = first.visitedNodes
  let generatedNodes = first.generatedNodes
  const reached = path.length > 0 ? path[path.length - 1] : start
  if (status === 'success' && !goal.isEnd(reached)) {
    // Joined a path: along it, then the rest of the way.
    const join = joins.get(reached.hash)
    const along = follow(movements, reached, join)
    path.push(...along)
    const from = along.length > 0 ? along[along.length - 1] : reached
    // Broken past node `held`: cut there, kept up to it.
    const held = join.from + along.length
    let kept = held < join.to ? join.nodes.slice(0, (held + 1) * 3) : null
    if (!goal.isEnd(from)) {
      const rest = search(from, movements, goal, thinkTimeout, tickTimeout)
      path.push(...rest.path)
      status = rest.status
      visitedNodes += rest.visitedNodes
      generatedNodes += rest.generatedNodes
      // The way on from where it broke.
      if (kept && rest.status === 'success') kept = withMoves(kept, rest.path)
    }
    if (kept) book.cut(world, join.id, kept)
  }

  if (status === 'success') book.add(world, withMoves(new Int32Array([start.x, start.y, start.z]), path))
  return { status, visitedNodes, generatedNodes, path }
}

module.exports = { pathBook, plan }

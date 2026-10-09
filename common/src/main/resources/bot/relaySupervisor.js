// What `node fleet.js --relay` runs: the process that never goes down. It holds the port and the
// mod's connections, and runs the relay itself (fleet.js) as a child, handing it each connection
// over IPC. Whatever ends the child (an update, an error, a crash of any kind) this one stays: an
// update has it start the child again on the new checkout with the same connection; anything else
// is said to the mod (CRASH) on the connections the child had, those are closed, and a new child
// takes the next knock. Kept small on purpose: an update never reloads it (only a restart by hand does).
const { fork } = require('child_process')
const net = require('net')
const path = require('path')
const protocol = require('./protocol')

// The child's exit code after a git reset: started again, handed its connection back.
const RESTART = 75

module.exports = function supervise (port) {
  // Connection id -> the mod's connection, from its knock until the child says it is done with it.
  // Never read here (paused on connect): its bytes are the child's.
  const held = new Map()
  let nextId = 1
  let child = null
  // The update the child exits for: { id: the connection, version: the mod's commit }.
  let restart = null

  const crash = text => {
    console.error(`relay supervisor: ${text}`)
    for (const connection of held.values()) {
      if (!connection.destroyed) connection.write(protocol.crash(text))
    }
  }

  // The last net under every error: said to the mod, never down.
  process.on('uncaughtException', err => crash(`the relay supervisor failed: ${err.stack ?? err}`))
  process.on('unhandledRejection', err => crash(`the relay supervisor failed: ${err?.stack ?? err}`))

  function hand (id, handoff) {
    child.send({ type: 'connection', id, handoff }, held.get(id), { keepOpen: true }, err => {
      if (err) crash(`could not hand connection ${id} to the relay: ${err.stack ?? err}`)
    })
  }

  function start (handoff) {
    const started = fork(path.join(__dirname, 'fleet.js'), process.argv.slice(2), {
      env: { ...process.env, MAPMCBOT_RELAY_CHILD: '1' },
      stdio: ['inherit', 'inherit', 'inherit', 'ipc']
    })
    child = started
    started.on('message', message => {
      if (message.type === 'done') {
        held.get(message.id)?.destroy()
        held.delete(message.id)
      } else if (message.type === 'restart') {
        restart = { id: message.id, version: message.version }
      } else {
        crash(`unknown message from the relay: ${JSON.stringify(message)}`)
      }
    })
    // Never started: no exit follows. The next knock tries again.
    started.on('error', err => {
      crash(`the relay could not start: ${err.stack ?? err}`)
      if (child === started && started.pid === undefined) child = null
    })
    started.on('exit', (code, signal) => {
      if (child === started) child = null
      const update = restart
      restart = null
      if (code === RESTART && update && held.has(update.id)) {
        console.log(`relay restarting on commit ${update.version}`)
        start(update.version)
        hand(update.id, update.version)
        return
      }
      // Gone some other way: the mod hears it, its connections end, and the next knock gets a new relay.
      crash(`the relay exited (code ${code}, signal ${signal})`)
      for (const connection of held.values()) connection.destroy()
      held.clear()
    })
    if (handoff === undefined) {
      for (const id of held.keys()) hand(id)
    }
  }

  const server = net.createServer({ pauseOnConnect: true }, connection => {
    const id = nextId++
    held.set(id, connection)
    connection.on('error', err => console.error(`relay supervisor: connection ${id} failed: ${err.message}`))
    if (child) hand(id)
    else start()
  })
  server.on('error', err => crash(`the relay's port failed: ${err.stack ?? err}`))
  server.listen(port, () => console.log(`relay listening on port ${port}`))
  start()
}

module.exports.RESTART = RESTART

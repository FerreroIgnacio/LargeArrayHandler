// Worker thread of the fleet's serializer pool: parses the columns the bots get into their slots of
// columns.bin, off the bots' threads (see sharedChunks.js). Each pool thread has a port to each
// serializer; a column's writes all go to the same one, so they land in order.
const { parentPort, workerData } = require('worker_threads')
const { openColumns, writeColumn, markReady } = require('./sharedChunks')
const { threadProfiler } = require('./profiler')

openColumns(workerData.columnsFile)
const scope = `node.thread:serializer:${workerData.index}`
const prof = threadProfiler(scope)
let written = 0

parentPort.on('message', message => {
  if (message.type === 'profile') {
    parentPort.postMessage({ profile: message.profile, rows: prof.rows([[scope, 'columns.written', 'n', written]]) })
    return
  }
  if (message.type !== 'client') throw new Error(`unknown message ${message.type} for a serializer thread`)
  // A pool thread's port: its writes, each answered once in its slot.
  message.port.on('message', write => {
    const header = new Int32Array(write.header, 0, 2)
    writeColumn({ ...write, header })
    if (write.ready) markReady(header)
    written++
    message.port.postMessage({ id: write.id })
  })
})

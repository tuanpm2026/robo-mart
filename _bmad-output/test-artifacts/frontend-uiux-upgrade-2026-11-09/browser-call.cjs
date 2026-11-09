// Pass browser helper data as CDP arguments, never as JavaScript source.
async function callPage(send, fn, ...values) {
  const scope = await send('Runtime.evaluate', { expression: 'globalThis' })
  const objectId = scope.result.objectId
  try {
    const result = await send('Runtime.callFunctionOn', {
      objectId,
      functionDeclaration: fn.toString(),
      arguments: values.map(value => ({ value })),
      awaitPromise: true,
      returnByValue: true,
    })
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails))
    return result.result.value
  } finally {
    await send('Runtime.releaseObject', { objectId })
  }
}
module.exports = { callPage }

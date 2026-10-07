import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const report = JSON.parse(readFileSync(new URL('../test-results/frontend.json', import.meta.url), 'utf8'))
assert.equal(report.success, true, 'Frontend run must succeed')
for (const key of ['numFailedTests', 'numPendingTests', 'numTodoTests', 'numFailedTestSuites']) {
  assert.equal(report[key] ?? 0, 0, key + ' must be zero')
}
const required = [
  ['chat-stream.test.js', 17],
  ['ChatWindow.test.js', 8],
  ['AppointmentPanel.test.js', 10],
  ['App.test.js', 6],
  ['EngineeringLab.test.js', 4],
]
for (const [file, minimum] of required) {
  const suite = report.testResults.find(item => item.name.replaceAll('\\', '/').endsWith('/tests/' + file))
  assert.ok(suite, file + ': missing suite')
  assert.equal(suite.status, 'passed', file + ': suite did not pass')
  assert.ok(suite.assertionResults.length >= minimum, file + ': missing test cases')
  assert.ok(suite.assertionResults.every(item => item.status === 'passed'), file + ': failed or skipped test')
}
console.log('FRONTEND_REPORT_OK tests=' + report.numPassedTests + ' suites=' + required.length)

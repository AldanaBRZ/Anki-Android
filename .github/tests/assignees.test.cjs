// SPDX-License-Identifier: GPL-3.0-or-later
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const workflow = fs.readFileSync(path.join(__dirname, '../workflows/assignees.yml'), 'utf8');
const script = workflow.match(/^          script: \|\r?\n([\s\S]*)$/m)[1]
  .replace(/^            /gm, '')
  // Render the original workflow expressions to reproduce its failure as well.
  .replaceAll('${{ github.event.pull_request.number }}', '7')
  .replaceAll('${{ github.event.repository.name }}', 'Anki-Android');
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const execute = new AsyncFunction('github', 'context', script);

function fixture({ issues = [], assignees = [{ login: 'fixture-maintainer' }], queryError, listError, removeError } = {}) {
  const context = {
    repo: { owner: 'float3', repo: 'Anki-Android' },
    issue: { number: 7 },
    payload: { pull_request: { number: 7, html_url: 'https://github.com/float3/Anki-Android/pull/7' } },
  };
  const queries = [];
  const reads = [];
  const removals = [];
  const github = {
    graphql: async (query, variables) => {
      queries.push({ query, variables });
      if (queryError) throw queryError;
      return { resource: { closingIssuesReferences: { nodes: issues } } };
    },
    rest: { issues: {
      listAssignees: async args => {
        reads.push(args);
        if (listError) throw listError;
        return { data: assignees };
      },
      removeAssignees: async args => {
        removals.push(args);
        if (removeError) throw removeError;
      },
    } },
  };
  return { context, queries, reads, removals, run: () => execute(github, context) };
}

test('cleanup queries the merged PR in its actual repository', async () => {
  const f = fixture({ issues: [{ number: 34, repository: { nameWithOwner: 'float3/Anki-Android' } }] });
  await f.run();
  assert.equal(f.queries[0].variables._url, f.context.payload.pull_request.html_url);
  assert.match(f.queries[0].query, /repository\s*\{\s*nameWithOwner\s*\}/);
  assert.deepEqual(f.reads, [f.context.repo]);
  assert.deepEqual(f.removals, [7, 34].map(issue_number => ({
    ...f.context.repo, issue_number, assignees: ['fixture-maintainer'],
  })));
});

test('a foreign linked issue cannot clear a local issue with the same number', async () => {
  const f = fixture({ issues: [
    { number: 12, repository: { nameWithOwner: 'ankidroid/Anki-Android' } },
    { number: 34, repository: { nameWithOwner: 'float3/Anki-Android' } },
  ] });
  await f.run();
  assert.deepEqual(f.removals.map(removal => removal.issue_number), [7, 34]);
});

test('repository identifiers are compared without case sensitivity', async () => {
  const f = fixture({ issues: [{ number: 34, repository: { nameWithOwner: 'FLOAT3/anki-android' } }] });
  await f.run();
  assert.deepEqual(f.removals.map(removal => removal.issue_number), [7, 34]);
});

for (const assignees of [[], null]) {
  test(`no assignees means no removal requests (${JSON.stringify(assignees)})`, async () => {
    const f = fixture({ assignees, issues: [{ number: 34, repository: { nameWithOwner: 'float3/Anki-Android' } }] });
    await f.run();
    assert.deepEqual(f.removals, []);
  });
}

for (const key of ['queryError', 'listError']) {
  test(`${key} propagates without changing any assignments`, async () => {
    const error = Object.assign(new Error('GitHub request failed'), { status: 403 });
    const f = fixture({ [key]: error });
    await assert.rejects(f.run(), actual => actual === error);
    assert.deepEqual(f.removals, []);
  });
}

test('a removal error fails the workflow and stops subsequent removals', async () => {
  const error = Object.assign(new Error('Removal denied'), { status: 403 });
  const f = fixture({ removeError: error, issues: [{ number: 34, repository: { nameWithOwner: 'float3/Anki-Android' } }] });
  await assert.rejects(f.run(), actual => actual === error);
  assert.deepEqual(f.removals.map(removal => removal.issue_number), [7]);
});

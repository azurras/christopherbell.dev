# Shared Folder Portal SDD Progress

Plan: `C:\Users\Christopher\Developer\builder\docs\implementation-plans\2026-07-17-christopherbell-dev-shared-folder-portal.md`
Branch base: `d45ff0ec63e774ac301299477dc02da44546632b`
Branch: `codex/shared-folder-worker`
Baseline: `gradlew.bat test :website:jsTest --console=plain` succeeded; browser tests 120 passed, 0 failed.

Workflow checkpoint: each numbered task is complete only after RED/GREEN implementation,
task/regression verification, approving independent review with all Critical/Important findings
resolved, commit, and push of the reviewed task branch. Do not start the next numbered task before
that remote checkpoint exists. Failed review/remediation cycles remain within the active task.

Task 1: complete (commits 9108f9c..0b025da, review clean; fresh `:website:test :website:jsTest` BUILD SUCCESSFUL, 122 JS tests passed)
Task 2: complete (commits 0b025da..ff23809, review clean; fresh `:website:test` BUILD SUCCESSFUL)
Task 3: complete (commits ff23809..8602985, final review approved with no findings; fresh `:website:test :website:jsTest` BUILD SUCCESSFUL, 141 JS tests, real Windows junction test enabled)
Task 4: complete (commits 8602985..8d1d3da; eighth whole-change review approved 0C/0I/1M; fresh `test :website:jsTest` BUILD SUCCESSFUL, 909 Java tests and 153 JS tests passed; reviewed branch checkpoint pushed)
Task 5: complete (commits 09d2f408..54b1bae2; twelfth whole-change review approved
0C/0I/0M; forced native/junction-enabled full gate passed 957 Java tests and 161 JS tests;
reviewed branch checkpoint pushed)
Task 6: complete (merged by PR #1218 as `d45ff0ec63e774ac301299477dc02da44546632b`;
final whole-change review approved 0 blockers/0 warnings; cross-platform CI and CodeQL passed)
Task 7: complete (commits d45ff0ec..405c8f83; final task review approved 0 Critical,
0 Important, 0 Minor; fresh full Windows gate passed 143 Pester tests with 0 failures and
3 elevation-gated skips; 20 PowerShell, 2 XML, and 2 JSON files parsed; reviewed branch
checkpoint pushed)
Task 8: complete (commits d503f66c..c1f728e4; final task review approved 0 Critical,
0 Important, 0 Minor; fresh full Windows gate passed 947 Java tests with 0 failures/errors
and 3 expected environment-gated skips plus 165 JavaScript tests with 0 failures; final diff
check clean; strict maintenance authority verified for the current single-Windows-host deployment;
reviewed branch checkpoint pushed)
Task 9: complete (commits 2a3ff56c..d8bf2fc3; final task review approved 0 Critical,
0 Important, 0 Minor; fresh isolated `clean test :website:sharedFolderVerification` gate passed
19/19 actions, 1,049 Java tests with 0 failures/errors and 3 expected platform skips, 165
JavaScript tests, worker Pester 56/56, and operations Pester 28/28 under both PowerShell 7
and Windows PowerShell 5.1 using exact Pester 5.9.0; final diff check clean; reviewed branch
checkpoint pushed)
Task 10: in progress (operational-test commits dc9965e4..287a28c9; alternate-port runtime
acceptance passed 41/41 against isolated roots, database, and port 8090; final runtime review
approved 0 Critical, 0 Important, 0 Minor after the bounded-range remediation; fresh aggregate
gate passed 1,053 Java tests with 0 failures/errors and 3 expected platform skips, 165 JavaScript
tests, worker Pester 56/56, and operations Pester 28/28 under both PowerShell hosts; PR #1219
passed CI and CodeQL and merged as 6ad5a0a; the first guarded production install rejected the
new feature flag before switching releases and was rolled back with the live website healthy,
feature disabled, worker service absent, and both data roots preserved; the production-fix branch
now accepts only a Boolean optional flag, defaults the process flag to false, and stops the worker
before every service/file mutation and on failure; focused production Pester passes 87/87 and a
fresh aggregate gate passes; final production-fix review, PR/CI/merge, installed-worker acceptance,
production rollout, and Builder closeout remain)

Minor findings for final review:
- Task 1: add an HTTP-level regression for `{read:false,write:true}` returning 400; service coverage already enforces it.
- Task 4: custom native `FileChannel.transferTo`/`transferFrom` should reject negative `position`
  or `count` with `IllegalArgumentException`; current product callbacks do not invoke these methods.

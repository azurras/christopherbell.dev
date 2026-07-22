# Shared Folder Portal SDD Progress

Plan: `C:\Users\Christopher\Developer\builder\docs\implementation-plans\2026-07-17-christopherbell-dev-shared-folder-portal.md`
Branch base: `9108f9cb936aa80eb10f235210c6b101e16496c4`
Branch: `codex/shared-folder-portal`
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
Task 6: pending
Task 7: pending
Task 8: pending
Task 9: pending
Task 10: pending

Minor findings for final review:
- Task 1: add an HTTP-level regression for `{read:false,write:true}` returning 400; service coverage already enforces it.
- Task 4: custom native `FileChannel.transferTo`/`transferFrom` should reject negative `position`
  or `count` with `IllegalArgumentException`; current product callbacks do not invoke these methods.

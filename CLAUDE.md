# Workflow: Opus plans, Sonnet and Haiku execute

- The main session runs on Opus (`.claude/settings.json`). It owns planning: understand the request, read the code, break the work into small concrete steps.
- Delegate each step to a subagent with exact files, the change wanted, and how to verify it. Independent steps can run in parallel.
  - `builder` (Sonnet, `.claude/agents/builder.md`): steps that need judgment — multi-file implementation, debugging, refactors, non-trivial tests.
  - `worker` (Haiku, `.claude/agents/worker.md`): mechanical steps — simple edits, searches, running tests or commands.
- Opus reviews each report, fixes or re-delegates anything wrong, and does the final check before committing.
- Keep tricky design decisions and final review on Opus.

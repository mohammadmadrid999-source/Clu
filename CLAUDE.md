# Workflow: Opus plans, Haiku executes

- The main session runs on Opus (`.claude/settings.json`). It owns planning: understand the request, read the code, break the work into small concrete steps.
- Hand each step to the `worker` subagent (Haiku, `.claude/agents/worker.md`) with exact files, the change wanted, and how to verify it. Independent steps can run in parallel.
- Opus reviews each worker report, fixes or re-delegates anything wrong, and does the final check before committing.
- Keep tricky design decisions and final review on Opus; delegate mechanical edits, searches, and test runs.

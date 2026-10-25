---
name: chat
description: Talk things through without changing anything. Only reading files, searching the code and the web are allowed.
argument-hint: your question or what you want to talk about
disable-model-invocation: true
allowed-tools:
  - Read
  - Glob
  - Grep
  - WebSearch
  - WebFetch
  - ToolSearch
# Everything except the tools above is removed for this turn. ToolSearch stays because
# WebSearch and WebFetch are loaded through it. If a new tool shows up, add it here.
disallowed-tools:
  - Bash
  - PowerShell
  - Edit
  - Write
  - NotebookEdit
  - Agent
  - Workflow
  - Skill
  - SendMessage
  - ListAgents
  - TaskStop
  - Monitor
  - AskUserQuestion
  - EnterPlanMode
  - ExitPlanMode
  - EnterWorktree
  - ExitWorktree
  - CronCreate
  - CronDelete
  - CronList
  - ScheduleWakeup
  - RemoteTrigger
  - PushNotification
  - Artifact
  - ArtifactComments
  - ArtifactData
  - DesignSync
  - ReportFindings
  - advisor
  - mcp__claude-vscode
  - mcp__claude-vscode__getDiagnostics
  - mcp__claude_ai_Claude_Docs
  - mcp__claude_ai_Claude_Docs__batch
  - mcp__claude_ai_Claude_Docs__create
  - mcp__claude_ai_Claude_Docs__delete
  - mcp__claude_ai_Claude_Docs__export
  - mcp__claude_ai_Claude_Docs__guide
  - mcp__claude_ai_Claude_Docs__query
  - mcp__claude_ai_Claude_Docs__read
  - mcp__claude_ai_Claude_Docs__update
  - mcp__codex
---

This is a conversation, not a task. The user wants to talk, think out loud, ask questions or get an explanation.

What the user said: $ARGUMENTS

Rules for this turn:
- The only tools you may use are Read, Glob, Grep, WebSearch and WebFetch (plus ToolSearch, only to load the two web
  tools). Do not run commands, edit or create files, start agents, publish anything, or commit.
- Look things up only when it helps the answer: read the code to be accurate, search the web for facts you are unsure of.
- If the user asks for a change, describe what you would do and where, then say they can ask for it outside /chat.
- Answer in plain, short language. If the text above is empty, ask what they would like to talk about.

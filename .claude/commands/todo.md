---
description: Write something down in TODO.md to be done later, without doing it now
argument-hint: what should be done later (leave empty to list the open todos)
---

The user wants this written down as a todo, to be implemented at some point in the future. Do NOT implement it, start it,
or change any file other than `TODO.md` at the repository root.

The todo, in the user's words: $ARGUMENTS

1. If the text above is empty, read `TODO.md` and list its open todos as a short numbered list of titles. Stop there.
2. Read `TODO.md` (create it from the header described in step 6 if it is missing). If an open todo already covers this,
   add the new details to that todo instead of writing a second one.
3. Otherwise add a new todo at the end of the "Open" section, in this form:

   ### <a short title>
   - **Added:** <today's date, YYYY-MM-DD>, by the user.
   - **What:** the request in the user's own words, quoted, then one or two plain sentences that make it precise.
   - **Context:** only what this conversation has already established: the files, elements or screens involved, decisions
     and rules the user has stated. Read files only as far as needed to name the right places. Do not research or design.
   - **Questions for later:** decisions the user will have to make when it is picked up, if any are obvious. Otherwise omit.

4. Pull `main` first, then commit only `TODO.md` with the message `Todo: <the title>` and push to `main`. `TODO.md` is
   outside the app, so this push does not build a release.
5. Reply in one or two sentences: the todo's title, and that it is written down and not done.
6. If you have to create `TODO.md`, start it with the title `# TODO`, one sentence saying it holds work the user decided
   to do later (added with `/todo`), and two sections: `## Open` and `## Done`.

When a todo is finished in a later session, move it to "Done" with the date and the release number, if there is one.

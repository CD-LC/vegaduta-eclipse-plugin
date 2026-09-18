# <img src="ai.vegaduta.ide.eclipse/icons/vegaduta64.png" height="40" alt=""> VegaDūta for Eclipse

VegaDūta for Eclipse puts your VegaDūta agents in a side view of the IDE.
You can chat with them, run your team's workflows, and send code from the
editor for them to explain, fix, refactor, test, document or review. You
choose what the agent sees: the open file, your selection, your uncommitted
git changes, your recent commit history, or the problems Eclipse has found
in a file. You can search your team's knowledge base from the chat, let an
answer walk you through the code it talks about, and switch on **Private
Mode** to keep your prompts and code on your machine.

Version **0.4.1** · Eclipse 2024-06 (4.32) or newer · Java 17+ ·
Windows, Linux, macOS

> **Preview.** This release is built and its core logic is covered by
> automated tests, but it has **not yet been run inside Eclipse** by the
> team (see [Known limitations](#known-limitations)). If something doesn't
> work, please tell us. The details you send help us fix it.

---

## Contents

- [What you need](#what-you-need)
- [Install](#install)
- [Sign in](#sign-in)
- [Using the chat](#using-the-chat)
- [Commands](#commands)
- [What gets sent, and when](#what-gets-sent-and-when)
- [Private Mode](#private-mode)
- [Your own local model server](#your-own-local-model-server)
- [Settings](#settings)
- [Troubleshooting](#troubleshooting)
- [Known limitations](#known-limitations)
- [For developers](#for-developers)

---

## What you need

- **Eclipse 2024-06 (4.32) or newer**, running on **Java 17 or newer**. Any
  Eclipse IDE package works; the plugin uses only parts every package ships.
- **A VegaDūta account** on your organisation's tenant, or a `vmcp_` API
  key from your admin. Chat, workflows and every editor command need one.
- **A browser engine for the chat view.** Eclipse draws the view with your
  operating system's own web engine:

  | OS | Engine the view uses | What to do |
  |---|---|---|
  | Windows 10/11 | Microsoft Edge WebView2 | Already installed on current Windows 11. If it's missing, the view tells you. Install the free *WebView2 Evergreen Runtime* from Microsoft, then restart Eclipse. |
  | Linux | WebKitGTK | Install your distribution's `webkit2gtk-4.1` (or `-4.0`) package if the view says it can't create a browser. |
  | macOS | WKWebView (built in) | Nothing to install. |

  On Windows the plugin never falls back to the old Internet Explorer
  control. That control can't run the chat and would only show an empty
  panel, so you get a clear message instead.
- **Optional: `git` on your PATH.** Only *Review My Changes* and the
  "git diff" attachment need it. Everything else works without it.

## Install

The plugin is distributed as an Eclipse **update site**. The admin or
download page gives you either a folder or a `.zip` file.

1. In Eclipse, open **Help > Install New Software…**
2. Click **Add…**, then:
   - for a `.zip` file: **Archive…** and choose the zip;
   - for a folder: **Local…** and choose the folder.
3. Tick **VegaDūta > VegaDūta for Eclipse**, click **Next**, accept the
   licence (Apache 2.0) and click **Finish**.
4. If Eclipse warns that the content is unsigned, choose **Install anyway**.
   This preview is not yet code-signed.
5. Restart Eclipse when asked.

Then open the view: **Window > VegaDuta > Open VegaDuta Chat** (or **Window >
Show View > Other… > VegaDuta > VegaDuta**). Drag its tab wherever you like;
Eclipse remembers the spot.

To uninstall: **Help > About Eclipse IDE > Installation Details**, select
*VegaDūta for Eclipse*, **Uninstall…**.

## Sign in

**With your VegaDūta account (recommended).**

1. Click **Sign in** in the chat view, or use **Window > VegaDuta > Sign In…**
2. Your web browser opens the VegaDūta sign-in page with a one-time code
   already filled in. Sign in there (email, Google, GitHub, whatever your
   organisation uses) and approve the request.
3. Go back to Eclipse. The view updates by itself within a few seconds.

The same code is shown in Eclipse's progress indicator (bottom-right),
so you can check it matches what the browser shows. The plugin never asks
for your password. It also opens no local network port, so sign-in works
over remote desktops and SSH sessions too. You can cancel a pending sign-in
from the **Progress** view.

Your sign-in is stored encrypted in **Eclipse secure storage**, never in
plain preferences, and never in the chat page itself. To sign out, click
**Sign out** in the chat view.

**With an API key.** Paste a `vmcp_` key into **Window > Preferences >
VegaDuta > API key**. API keys have fewer features than a full sign-in:
replies arrive all at once instead of word by word.

**Which server?** By default the plugin connects to **production,
`vegaduta.ai`**. Your admin may ask you to choose *staging* or a *custom*
address in the preferences instead.

## Using the chat

- **Pick an agent** at the top of the chat and type your message. Replies
  stream in as they're written.
- **Attach context.** Along with your message, the chat can send context
  that Eclipse collects for it:

  | Attachment | What Eclipse sends |
  |---|---|
  | Active file | The whole document in the editor you last used, including unsaved edits. The label shows its workspace path, e.g. `my-app/src/Main.java`. |
  | Selection | The text currently selected in that editor. |
  | Git diff | The output of `git diff HEAD` for the repository that contains that file: your staged and unstaged changes to tracked files. New files you haven't `git add`-ed are not included. |
  | Problems | The errors, warnings and infos Eclipse shows in the **Problems** view for that file, with line numbers. |
  | Recent commits | The last 30 commits (`git log -n 30`) of the repository that contains that file: short hash, date, subject and message body, newest first. Author names and e-mail addresses are **not** included. Useful for writing a commit message, release notes or a standup in your team's own style. |

  The IDE terminal's selection is not available as an attachment in
  Eclipse: its Terminal view has no public API for another plugin to read
  its selection.

  Each attachment is capped at 24,000 characters. If something was cut
  short, the chat marks it as truncated. If Eclipse can't supply an
  attachment, the chat shows the reason, e.g. "Nothing is selected in the
  active editor" or "This repository has no commits yet". Nothing is
  dropped silently.
- **Use a reply.** Code blocks in a reply can be:
  - **inserted** at your cursor in the active editor, replacing any
    selection. If no editor can take it (none open, or read-only), the code
    is copied to the clipboard and the line under the chat tells you;
  - **copied** to the clipboard;
  - **opened as a new file**: a new editor tab opens with the code, backed
    by a scratch file in your system's temp folder. Use **File > Save As…**
    to keep it in a project;
  - **run in a sandbox** (Python, JavaScript/TypeScript via Node, or Bash),
    if your organisation has the sandbox enabled and your admin has given
    you the role for it. Otherwise the chat tells you who to ask.
- **Run a workflow** from the chat's workflow list. The plugin follows the
  run until it finishes. After 10 minutes it stops following and tells you
  to check the result in the VegaDūta web console.
- **Search your knowledge base.** When the chat searches your tenant's
  knowledge base, Eclipse sends the query to VegaDūta
  (`POST /api/knowledge/search`) with your sign-in and hands the matching
  passages back to the chat. This needs a full browser sign-in: an API key
  alone can't reach the knowledge base, so with only a key the chat doesn't
  offer it.
- **Follow a code tour.** When an answer points at lines in your code, the
  chat can ask Eclipse to show them: Eclipse opens the file in its editor
  and selects those lines. Only files in your open workspace projects are
  opened. A path that leaves the project (`..`), or an absolute path outside
  every open project, is refused, and the line under the chat says why.

## Commands

Right-click in any text editor (including the Java editor) and open the
**VegaDuta** submenu, or use the **Window > VegaDuta** menu. Every command
can also be found with **Ctrl+3** / **⌘3** (Quick Access) by typing its name,
and given a key binding in **Preferences > General > Keys** (category
*VegaDuta*).

| Command | Needs | What it does |
|---|---|---|
| **Open VegaDuta Chat** | – | Opens or focuses the chat view. *(Window menu)* |
| **Sign In…** | – | Starts browser sign-in (see [Sign in](#sign-in)). *(Window menu)* |
| **Explain Selection** | a selection | Puts the selected code in the chat box with an instruction to explain it and point out pitfalls. |
| **Fix Selection** | a selection | Same, asking for the bugs to be found and fixed, with a note on what changed. |
| **Refactor Selection** | a selection | Same, asking for a clearer, behaviour-preserving rewrite. |
| **Write Tests for Selection** | a selection | Stages a "write tests" prompt and attaches your selection. The agent is asked to match the test framework already used in your project. |
| **Add Docs to Selection** | a selection | Stages an "add documentation" prompt and attaches your selection. |
| **Review My Changes** | `git`, a repository | Stages a code-review prompt and attaches your uncommitted diff (`git diff HEAD`) for the repository that holds the file you're editing. With no editor open, it uses the repository your open projects are in, provided there's exactly one. |

The selection commands are greyed out until you select some text. The
instructions they use are word-for-word the same as in the VegaDūta
VS Code extension, so the same command gives the same prompt in either IDE.

## What gets sent, and when

- **Nothing is sent without you.** Every command above only *fills in* the
  chat box and its attachments. You read it, edit it if you want, and press
  **Send**. A right-click never uses up your quota by itself.
- **Only what you attach is sent.** The plugin does not upload your
  workspace, index your project, or read files in the background. It reads
  the editor, selection, diff or problems only when an attachment is
  requested.
- **Your sign-in token stays in Eclipse.** Chat requests are made by the
  plugin itself, not by the web page inside the view, so the page never
  sees your token.
- **A knowledge-base search sends its query** to VegaDūta, and nothing
  else. It never runs while Private Mode is on.

## Private Mode

Turn it on with the **Private Mode** button in the VegaDuta view's toolbar,
or with the chat's own Private Mode switch. The two stay in step, and the
setting is remembered when you restart Eclipse. While it's on, the line at
the top of the view says so.

While Private Mode is on, **no prompt, code, attachment or search query
leaves your machine** through this plugin:

- **Blocked:** hosted chat with your VegaDūta agents, knowledge-base
  search, running code in the sandbox (it runs on VegaDūta's servers), and
  workflow runs. Eclipse refuses these itself, before anything is sent, and
  the chat shows why.
- **Still allowed:** the status check of your own local model server (see
  below), which sends it nothing of yours, plus signing in and loading the
  list of your agents and workflows. None of those carry your prompts or
  code.

In Eclipse 0.3.0 the chat has no on-device mode, so **while Private Mode is
on the chat can't answer**: every chat reply comes from the hosted service.
Use Private Mode when you want to be certain nothing you type or attach is
sent, and turn it off to chat.

Private Mode is not "offline mode": Eclipse can still talk to VegaDūta for
sign-in and the agent list. What it guarantees is that your content stays
put.

## Your own local model server

In Eclipse 0.3.0 **the chat does not run models on your machine.** Every
chat reply comes from your VegaDūta tenant, so you need an account or API
key. There is no free, account-free chat mode in this version of the
Eclipse plugin.

What the plugin *does* offer: if you run an OpenAI-compatible model server
yourself (for example [Ollama](https://ollama.com) at
`http://127.0.0.1:11434`), set its address in **Preferences > VegaDuta >
Local inference server**. The line under the chat then shows whether that
server is reachable and which model it serves. Nothing is sent to it. It's a
status check only.

Why not more: the chat page runs inside Eclipse from a local file. Your
model server would see that page's requests as coming from an unknown
origin and, in its default setup, reject them. Offering a mode that would
fail without saying so is worse than not offering it. In-browser GPU models
(WebGPU) are switched off in the IDE for the same reason: none of the three
engines above has been verified to run them.

## Settings

**Window > Preferences > VegaDuta** (on macOS: **Eclipse > Settings… >
VegaDuta**):

| Section | Setting | What it's for |
|---|---|---|
| VegaDuta tenant | Environment | *Production* (`vegaduta.ai`, default), *Staging* (`vegaduta.xyz`), or *Custom*. |
| | Custom API base / auth base | Only for *Custom*. Your admin gives you these. They must be public addresses; `localhost` is refused. |
| | API key (vmcp_) | Alternative to signing in. |
| Local inference server | Base URL, Model | Your own model server, for the status check described above. Leave blank to turn it off. |
| Coding agent | Endpoint, model, max steps, provider key, auto-approve | **Not used yet.** Kept for parity with the JetBrains plugin; they change nothing in 0.3.0. |

Changing a setting reloads the chat view.

## Troubleshooting

**The view is empty, or says it could not create a browser.**
Install the engine for your OS (see [What you need](#what-you-need)) and
restart Eclipse. Details are in **Window > Show View > Error Log**.

**The view says "Chat UI not bundled".**
The copy you installed was built without the chat page. Get a complete build
from whoever gave you the update site.

**Sign-in fails with "Offline tokens are not allowed for this user"** (or the
browser shows that message after you approve).
This was a problem with some **accounts** on the VegaDūta server, not with
the plugin, and it was fixed on the server on **18 September 2026**. If you
still see it, **ask your VegaDūta admin** to check your account: it needs
the realm's default roles. Then sign in again. Reinstalling the plugin or
changing its settings won't help.

**"Review My Changes" says git is not installed.**
Install git, make sure the `git` command works in a terminal, and restart
Eclipse. Eclipse only sees the PATH it was started with. Eclipse's own Git
integration (EGit) is not used and not required.

**"Review My Changes" says there are no uncommitted changes, but I added a
file.** New, never-added files aren't part of `git diff HEAD`. `git add` the
file (you don't need to commit) and try again.

**Git refuses the repository with "dubious ownership".**
The repository belongs to a different OS user than Eclipse. Run the
`git config --global --add safe.directory <path>` command git suggests.

**Insert doesn't put the code in my editor.**
Click into the editor first so it's the active one. If no editable text
editor is active, the plugin copies the code to the clipboard instead and
says so in the line under the chat.

**Running code says I'm not allowed.**
The sandbox is limited to certain roles on your tenant. Ask your admin.

## Known limitations

Plainly stated, so you know what to expect from this preview:

- **Not yet run inside Eclipse by the team.** The plugin compiles against
  Eclipse 2024-06, and its logic (message handling, sign-in token handling,
  streaming, context collection, git diff and log, truncation, code-tour
  path checks, knowledge-search mapping, Private Mode) passes 201 automated
  tests. It has not yet been opened in a running Eclipse on any operating
  system, and no sign-in, chat or command has been exercised end to end.
  Expect rough edges.
- **No on-device chat** (see [above](#your-own-local-model-server)).
- **No inline code completions** as you type.
- **No autonomous coding agent** (multi-step edits and commands). The
  JetBrains plugin has one; Eclipse doesn't yet.
- **Commit messages** are not written into Eclipse's Git Staging view.
  That view belongs to the optional EGit plugin, and VegaDūta doesn't depend
  on it. If the chat produces a commit message, it's copied to the
  clipboard for you to paste.
- **The Java editor's right-click menu** entry is set up from Eclipse's
  documentation and hasn't been seen yet. If it's missing there, use the
  **Window > VegaDuta** menu, which has every command.

---

## For developers

Everything below is for building or changing the plugin.

### What this is

The fourth VegaDūta client, alongside `../vscode`, `../chrome` and
`../jetbrains`. It hosts the **same** chat webview
(`clients/shared/dist/webview`) in an SWT `Browser` and implements the
**host** side of `clients/shared/src/webview/protocol.ts`. The shared
contract is consumed here, never forked. The layout mirrors the JetBrains
plugin: host-side SSE, tokens that never enter the page, the same `edge.*`
seed into the page's kv, and "queue posts until the page says `ready`".

### Protocol coverage (host side)

| Message | Where | Notes |
|---|---|---|
| `ready` → `init` | `WebviewBridge.sendInitThenFlush` | `init` is delivered **before** anything queued while the page booted (e.g. a `ui.prefill` from the command that opened the view), under the queue lock so no concurrent post can overtake it. A page reload bumps a generation counter so a stale `init` never marks the new page ready. |
| `init.capabilities` | `ContextItems.hostCapabilities` | `context: [file, selection, diff, diagnostics, gitlog]`, `insert`, `newFile`, `runCode`, `reveal`, `privateMode` true; `commitMessage` **false** (EGit is optional; no dependency taken); `knowledge` true unless the only credential is a `vmcp_` API key (true when signed out, so a later sign-in works without a reload; the host then answers `signed-out`). |
| `context.request` → `context.result` | `WebviewBridge.handleContextRequest` | Editor state is read on the SWT UI thread (`ui/EditorContext`, `syncExec` if not already on it); git, truncation and labels run on the bridge executor (`context/ContextAssembler`, `context/GitDiff`, `context/GitLog`). Every requested kind comes back as an item or a `missing` reason; `terminal`, `page` and `pageElements` are always `missing` ("Not available in Eclipse."). |
| `ui.reveal` | `EditorBridge.reveal` | Path checked by `context/RevealPaths` (relative only, any `..` refused, absolute only inside an open project's location), then resolved against **open workspace projects only** (`project/path` first, else a unique project-relative match), opened with `IDE.openEditor` and selected via `IDocument` line offsets on the UI thread. 1-based inclusive in; end clamped; refusals go to the status line. |
| `knowledge.search` → `knowledge.result` | `WebviewBridge.handleKnowledgeSearch`, `api/Knowledge`, `ApiClient.searchKnowledge` | JWT only. `topK` clamped to 1..20 (default 5), malformed collection ids dropped, query trimmed and capped at 2,000 chars. Hits keep `id, source, content, collectionId, documentId, distance` (explicit JSON `null`s). Reasons: 401 `signed-out`, 403 `forbidden`, transport/404/5xx `unavailable`, else the server's message. |
| `privacy.mode` → `privacy.state` | `privacy/PrivacyGate`, `WebviewBridge.setPrivateMode` | Persisted in the instance preference `privateMode`. Every webview message passes `PrivacyGate.check` first; while on, `chat.send`, `knowledge.search`, `sdlc.run` and `workflow.run` are refused with their own failure message. `privacy.state {private, enforced: true}` answers every toggle and follows every `init`. The view toolbar toggle and `setContentDescription` mirror it. Eclipse has no hosted completions, hosted editor commands or code validation to block. |
| `ui.prefill` | `WebviewBridge.prefill` | Sent by *Write Tests*, *Add Docs* (`["selection"]`) and *Review My Changes* (`["diff"]`), always `send: false`. |
| `ui.insert` | `EditorBridge.insertAtCaret` | Falls back to clipboard + status line when there's no editable text editor. |
| `ui.newFile` | `EditorBridge.openInNewEditor` | Scratch file in `java.io.tmpdir/vegaduta/`, opened with `IDE.openEditorOnFileStore`. Stock Eclipse has no public untitled-editor API. Falls back to clipboard + status line. |
| `ui.setCommitMessage` | `WebviewBridge` | Not advertised; if received anyway, clipboard + status line. |
| `chat.*`, `workflow.*`, `sdlc.run`, `engine.status`, `auth.*`, `ui.copy`, `ui.openExternal`, `selection.context` | `WebviewBridge` | As in 0.1.0. |

`git log` (`context/GitLog`) uses the same process rules as the diff below:
`git -c core.quotepath=off -c log.showSignature=false --no-pager log -n 30
--no-color --date=short --format=%x1e%h%x1f%ad%x1f%s%x1f%b` - ASCII record
and unit separators, so no subject can break the parse. Bodies are capped at
1,500 chars each, and the rendered item is cut at a **commit boundary** to
fit 24,000 chars, with the label saying "12 of 30".

`git diff HEAD` details (`context/GitDiff`): run with `ProcessBuilder` in the
directory that holds `.git` (a `.git` *file* counts, for worktrees and
submodules), with `--no-color --no-ext-diff -c core.quotepath=off`,
`GIT_PAGER=cat`, `GIT_TERMINAL_PROMPT=0`, a 15 s timeout, and stdout drained on
its own thread with a byte cap so a huge diff can never block git on a full
pipe. Typed failures: git missing, not a repo, no commits yet, dubious
ownership, timeout, other (first stderr line).

### The SSE framing rule

`api/SseEventReader.java` is a line-for-line port of `createSseEventReader()`
in `clients/shared/src/api/sse.ts`, and `ApiClient` feeds it whole lines:

1. Strip **exactly five** characters of `data:`. Spring writes no separator
   space, so a one-space chunk arrives as `"data: "` and its payload *is* that
   space.
2. **One backend chunk is one EVENT, not one line.** `SseEmitter` rewrites
   every LF in a payload as `"LF data:"`, so a multi-line reply arrives as
   several `data:` lines ended by one blank line, and they are rejoined with
   LF (the bug fixed in commit `81416d12`). `clients/jetbrains`'s
   `ApiClient.streamChat` still reads line-per-chunk; do **not** copy that
   loop.

### How the page is loaded

- `<script type="module" src="chat.js">` cannot load from a `file://` URL
  (module fetches are CORS-checked), so the script is **inlined**, keeping
  `type="module"`, because `chat.js` is ESM and ends in an `export`.
- The assembled document is written to the bundle's state location and loaded
  with `setUrl()`, **not** `Browser.setText()`: on Windows `setText()` goes
  through WebView2's `NavigateToString`, which refuses strings over about 2 MB,
  and `chat.js` is ~6.7 MB.
- Webview→host is an SWT `BrowserFunction` named `__vegadutaToHost`, wrapped
  in-page by a `window.cefQuery` shim, so the shared `detectTransport()` takes
  its existing JetBrains branch. `init.platform` is reported as `"jetbrains"`
  because the protocol's `EnginePlatform` union has no Eclipse member and
  extending it would fork the contract.
- The page's kv is seeded with `edge.backend="ollama"` and
  `edge.modelOverride="hosted"` before the bundle runs, so the WebGPU path can
  never half-start; `init.hostLocalEngine` is `false`. `api/LocalServerProbe`
  does the local-server check from Java, where no CORS applies.

### Build

```bash
# 1. The chat webview bundle - always first.
cd clients/shared && npm install && npm run build:webview

# 2. Plugin, tests, feature and p2 update site. Maven on JDK 17.
JAVA_HOME=/path/to/jdk-17 MAVEN_OPTS="-Dhttps.protocols=TLSv1.2,TLSv1.3" \
  mvn -f clients/eclipse/pom.xml clean verify
```

Step 2 copies `clients/shared/dist/webview/{index.html,chat.js,chat.css}` into
`ai.vegaduta.ide.eclipse/webview/` during `process-resources` (gitignored build
output). Skipping step 1 still builds; the view then says the chat UI is
missing instead of rendering a blank panel.

Tycho 4.0.13, target platform `https://download.eclipse.org/releases/2024-06`
(declared in the parent `pom.xml`). Tycho 4.0.x is the last line that runs on
JDK 17. A resolution error naming `download.eclipse.org` or a missing
`org.eclipse.*` unit, before any `Compiling …` line, is a **network**
failure, not a code failure.

Output: the p2 site at
`ai.vegaduta.ide.eclipse.repository/target/repository/` plus a zip of it next
to it. Versions: bundle `0.4.1.qualifier`, feature `0.4.1.qualifier`, poms
`0.4.1-SNAPSHOT`; keep all three in step (bump with `mvn org.eclipse.tycho:tycho-versions-plugin:set-version -DnewVersion=<v>-SNAPSHOT`).

Bundle dependencies (`Require-Bundle`) are all part of the Eclipse Platform
that every IDE package ships: `org.eclipse.ui`, `swt`, `jface`,
`core.runtime`, `core.expressions`, `equinox.security`, `jface.text`,
`ui.workbench.texteditor`, `core.resources`, `core.filesystem`, `ui.ide`.
Don't add EGit/JGit or a JSON library: an unresolvable `Require-Bundle`
makes the whole plugin uninstallable. That is why `json/Json.java` is
hand-written and covered directly by `JsonTest`.

Icons: `ai.vegaduta.ide.eclipse/icons/*` are generated by
`clients/scripts/generate_brand_icons.py` (the winged emblem, own colours).
Don't hand-edit them. `icons/` is in `build.properties` `bin.includes`; before
0.3.0 it wasn't, and the built jar shipped without the view icon.

### Tests

```bash
mvn -f clients/eclipse/pom.xml test
```

**201 tests, 0 failures, 0 skipped** on the last run (JDK 17, Windows, git
2.x on PATH). They live in `ai.vegaduta.ide.eclipse.tests`, a plain JUnit 5 /
surefire module: everything covered is pure Java with no SWT and no OSGi.

| Suite | Tests | Covers |
|---|---:|---|
| `SseEventReaderTest` | 15 | The framing rules above, ported from `clients/shared/test/sse.test.ts` |
| `ApiClientStreamTest` | 11 | Byte decoding across reads, unterminated final event, typed IO failure, `dev/v1` path swap |
| `ModelsTest` | 37 | Wire decoding, terminal run states, sandbox-language and file-extension maps, local-server probe wording |
| `EnvironmentsTest` | 21 | RULE 0: `.ai` = production, `.xyz` = staging, loopback refused for platform bases |
| `DeviceFlowTest` | 16 | Device-authorization parsing, poll classification, token parsing, expiry margin |
| `JsonTest` | 11 | The hand-rolled JSON layer, including `<script>`-embedding escapes |
| `WebviewPageTest` | 11 | Page assembly, edge seed, delivery-call encoding |
| `ContextItemsTest` | 12 | Truncation to 24,000 (surrogate-safe), chip labels, problem formatting and ordering, kind filtering (canonical spelling for `pageElements`), the advertised capabilities |
| `ContextAssemblerTest` | 15 | Items vs `missing` reasons for every kind incl. `gitlog`, `terminal`, `pageElements`; git root from the active file vs the open-projects fallback; overflow labelling |
| `GitDiffTest` | 10 | The exact command, root walk (incl. a `.git` file), failure classification, and **real git** in temp repos: clean tree, modified files, no-commits repo, a ~600 KB diff drained without blocking and capped |
| `GitLogTest` | 11 | The exact command, separator parsing (incl. a record cut by the capture cap), rendering, per-body cap, commit-boundary truncation that never exceeds the limit, failure wording, and **real git**: 3 commits newest first with bodies and no author, and a no-commits repo |
| `RevealPathsTest` | 6 | `..` refused in every form, absolute/drive/UNC/URI/stream/NUL refused, absolute paths contained segment-wise (`app` does not contain `app-secrets`, `app/../x` normalized before the check), line mapping and clamping |
| `KnowledgeTest` | 7 | `SearchResultResponse` → `KnowledgeHit` (explicit nulls, `chunkIndex` dropped, unusable entries skipped), request body, `topK` clamp, UUID filtering, query trimming, reasons, `knowledge.result` shape |
| `PrivacyGateTest` | 7 | Exactly the four hosted types blocked, every local type allowed, per-type replies carrying none of the user's content, persistence only on change, `privacy.state`, the view's wording |
| `LanguageIdsExtensionTest` | 11 | languageId → scratch-file extension, fallback to `txt`, round-trip with `forFileName` |

The real-git tests are skipped, not failed, on a machine without git.

### What is not verified

*Compiled ≠ tested ≠ run.* As of 0.3.0:

- **Nothing has run in an Eclipse IDE.** No workbench was launched; no view
  opened; no page rendered in any SWT engine (WebView2 / WebKitGTK /
  WKWebView); no message crossed the `BrowserFunction` ↔ `cefQuery` shim.
- **The new host code is compiled, not exercised:** `EditorContext` (editor,
  selection and marker reads), `openInNewEditor`, the insert fallback, the
  `PrefillHandler` commands and their menu entries, and the init-before-queue
  ordering. Their pure halves (`ContextAssembler`, `ContextItems`, `GitDiff`)
  are tested.
- **Wave 2 host code is compiled, not exercised:** `EditorBridge.reveal`
  (workspace resolution, `IDE.openEditor`, line selection), the knowledge
  search HTTP call, the Private Mode toolbar toggle and view description, and
  the preference flush. Their pure halves (`RevealPaths`, `Knowledge`,
  `PrivacyGate`, `GitLog`) are tested. The shared chat UI's use of
  `ui.reveal`, `knowledge.search` and `privacy.mode` has not been observed
  against this host.
- **The rewritten shared chat UI's handling of `ui.prefill`,
  `context.result` and `capabilities` has not been observed against this
  host.**
- **No live call** to `api.vegaduta.ai` / `api.vegaduta.xyz`: no sign-in, chat,
  workflow or sandbox run. `ISecurePreferences` has never stored anything.
- **The p2 site has not been installed** into an Eclipse. It builds and
  contains the feature, the plugin (with `webview/` and `icons/`) and the
  category.
- `localStorage` on a `file://` origin, and the Java editor's
  `#CompilationUnitEditorContext` menu id, are both unobserved.

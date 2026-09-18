# VegaDūta for Eclipse

Source code for the **VegaDūta** Eclipse IDE plug-in: chat with your VegaDūta
agents in a side view, run workflows, send code from the editor (Explain, Fix,
Refactor, Write Tests, Add Docs, Review My Changes), attach the open file,
selection, git diff or problems to any message, and use 18 offline Instant
tools. Sign-in uses the OAuth 2.0 device grant (RFC 8628).

Licensed under **Apache-2.0** - see [LICENSE](LICENSE) and [NOTICE](NOTICE).

## Layout

```
eclipse/   The Eclipse plug-in (Java 17, Tycho): bundle, feature, p2 update
           site and tests. This is what is installed from the update site.
shared/    The shared TypeScript core the chat view is built from - API client,
           SSE reader, device-flow auth, host<->webview protocol, the chat UI
           and the on-device engine layer. eclipse/ consumes only the built
           bundle (shared/dist/webview), copied into the plug-in at build time.
```

`shared/` is extracted from the VegaDūta platform monorepo, where the same core
also backs the VS Code, Chrome and JetBrains clients. Only the Eclipse plug-in
and the code it is built from are published here.

## Build from source

Requires JDK 17+, Maven 3.9+ and Node 20+.

```bash
# 1. Build the chat view bundle (shared/dist/webview)
cd shared
npm ci
npm run build:webview

# 2. Build and test the plug-in; the p2 update site lands in
#    eclipse/ai.vegaduta.ide.eclipse.repository/target/repository/
cd ../eclipse
mvn clean verify
```

## Install

From the update site: *Help > Install New Software... > Add...* and enter the
VegaDūta update-site URL, or *Add... > Archive...* and pick the built
`ai.vegaduta.ide.eclipse.repository/target/*.zip`. Requires Eclipse 2024-06
(4.32) or newer.

## Privacy

When you are signed in and send a message, the prompt and the context you
attached are sent to the VegaDūta service (api.vegaduta.ai). Private Mode keeps
everything on your machine; the Instant tools never send anything. See
<https://vegaduta.ai/privacy>.

## Issues

<https://vegaduta.ai>

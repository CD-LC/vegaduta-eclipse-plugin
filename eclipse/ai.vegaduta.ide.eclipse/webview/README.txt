This directory receives the shared chat webview bundle at build time.

The plugin's pom.xml copies clients/shared/dist/webview/{index.html,chat.js,
chat.css} here during process-resources, the same way the JetBrains plugin's
copyWebview Gradle task copies it into its resources. Build the bundle first:

    cd clients/shared && npm install && npm run build:webview

Those three files are build output and are gitignored. This README is the one
tracked file in the directory, and it is tracked ON PURPOSE: build.properties
lists webview/ in bin.includes, and Tycho fails the build outright if a
bin.includes entry matches nothing. Keeping one file here means the plugin
builds (with no chat UI, and the view says so) before anyone has ever run the
npm build.

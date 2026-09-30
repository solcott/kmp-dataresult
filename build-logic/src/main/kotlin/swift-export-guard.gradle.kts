// For modules that consumers export to Swift wholesale (dataresult and uistate). Adds
// `checkSwiftExport` to `check`, so the constructs Swift export can't handle fail this build and
// CI rather than only a consumer's iOS build. See CheckSwiftExport.
plugins { base }

val checkSwiftExport by
  tasks.registering(CheckSwiftExport::class) {
    description = "Fails on source constructs that consumers' Swift export can't handle."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    // Every main source set, not just commonMain: appleMain and friends are exported too.
    sources.from(fileTree("src") { include("*Main/**/*.kt") })
    marker = layout.buildDirectory.file("swift-export/check-ok.txt")
  }

tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(checkSwiftExport) }

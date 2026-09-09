plugins {
  id("kmp-library")
  id("kmp-published")
}

kotlin {
  // Countries' :apple module runs its Swift-export verification on macosArm64, so everything it
  // consumes needs this slice. See the note in `kmp-library` for why it isn't in the shared set.
  macosArm64()
}

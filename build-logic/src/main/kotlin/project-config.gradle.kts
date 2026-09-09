// Shared configuration applied to every project through the convention plugins. Centralizes
// formatting so nothing has to reach for an allprojects {} block.
plugins { id("com.ncorti.ktfmt.gradle") }

ktfmt {
  googleStyle()
  removeUnusedImports = true
}

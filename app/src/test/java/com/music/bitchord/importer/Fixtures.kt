package com.music.bitchord.importer

/** Saved service responses under `src/test/resources/import/`. */
object Fixtures {
    fun read(name: String): String =
        requireNotNull(Fixtures::class.java.classLoader?.getResource("import/$name")) { "missing fixture $name" }
            .readText()
}

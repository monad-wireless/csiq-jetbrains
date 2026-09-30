package io.monadcount.csiq.format

import java.util.Locale

/**
 * Number formatting that does not follow the machine's locale.
 *
 * Every number this plugin prints is a measurement, and it sits beside
 * timestamps that `java.time` always writes with a dot. On a machine set to a
 * comma-decimal locale, Kotlin's own `String.format` yields `0,000 s` next to
 * `14:12:58.382`, which reads as two different conventions in one axis and is
 * ambiguous wherever a comma also separates values.
 *
 * `Locale.ROOT` is the technical convention: a dot for the decimal separator
 * and no digit grouping unless the format asks for it.
 */
fun String.fmt(vararg args: Any?): String = String.format(Locale.ROOT, this, *args)

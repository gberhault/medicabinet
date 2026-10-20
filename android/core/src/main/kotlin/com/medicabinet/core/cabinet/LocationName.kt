package com.medicabinet.core.cabinet

/** Why a location name was rejected. [kind] is the platform-neutral name shared with iOS and the fixtures. */
enum class LocationNameError(val kind: String) {
    EMPTY("empty"),
    TOO_LONG("tooLong"),
    DUPLICATE_NAME("duplicateName")
}

class LocationNameException(val error: LocationNameError) : IllegalArgumentException(error.kind)

object LocationName {
    /**
     * In Unicode code points: SQLite's `length()` (the v1 `CHECK`, at most 100) counts code points, so this
     * limit can never fail the database check.
     */
    const val MAX_LENGTH = 50

    /**
     * Returns the trimmed name as typed. [existing] lists the other locations' names (not deleted);
     * [renaming] is the name of the location being renamed, left out of the duplicate check.
     *
     * @throws LocationNameException with the first failing rule: empty, too long, duplicate name.
     */
    fun validate(raw: String, existing: List<String>, renaming: String? = null): String {
        val name = TextNormalizer.trimmed(raw)
        errorOf(name, existing, renaming)?.let { throw LocationNameException(it) }
        return name
    }

    private fun errorOf(name: String, existing: List<String>, renaming: String?): LocationNameError? {
        val key = TextNormalizer.normalize(name)
        val others = existing.toMutableList()
        if (renaming != null) others.indexOfFirst { it == renaming }.takeIf { it >= 0 }?.let(others::removeAt)
        return when {
            key.isEmpty() -> LocationNameError.EMPTY
            name.codePointCount(0, name.length) > MAX_LENGTH -> LocationNameError.TOO_LONG
            others.any { TextNormalizer.normalize(it) == key } -> LocationNameError.DUPLICATE_NAME
            else -> null
        }
    }
}

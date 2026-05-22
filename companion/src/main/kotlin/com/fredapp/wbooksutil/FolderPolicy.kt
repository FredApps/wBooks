package com.fredapp.wbooksutil

object FolderPolicy {
    const val MAX_FOLDERS = 64
    const val MAX_NAME_LENGTH = 40

    data class Validation(val name: String?, val error: String?) {
        val isValid: Boolean get() = error == null
    }

    fun validateName(raw: String, allowRoot: Boolean = false): Validation {
        val name = raw.trim().trim('/', '\\')
        if (name.isEmpty()) {
            return if (allowRoot) Validation("", null) else Validation(null, "Folder name required")
        }
        if (name.length > MAX_NAME_LENGTH) {
            return Validation(null, "Folder names can be at most $MAX_NAME_LENGTH characters")
        }
        if (name.any { it.code < 32 } || name.any { it in invalidChars }) {
            return Validation(null, "Folder names cannot contain path or reserved characters")
        }
        if (name == "." || name == ".." || name.uppercase() in reservedNames) {
            return Validation(null, "That folder name is reserved")
        }
        return Validation(name, null)
    }

    fun validateCreate(raw: String, existingFolders: Collection<String>): Validation {
        val base = validateName(raw)
        val name = base.name ?: return base
        if (existingFolders.any { it.equals(name, ignoreCase = true) }) {
            return Validation(null, "Folder already exists")
        }
        if (existingFolders.size >= MAX_FOLDERS) {
            return Validation(null, "Folder limit reached ($MAX_FOLDERS)")
        }
        return Validation(name, null)
    }

    fun validateRename(oldName: String, rawNewName: String, existingFolders: Collection<String>): Validation {
        val base = validateName(rawNewName)
        val name = base.name ?: return base
        if (name.equals(oldName, ignoreCase = true)) return Validation(name, null)
        if (existingFolders.any { it.equals(name, ignoreCase = true) }) {
            return Validation(null, "Folder already exists")
        }
        return Validation(name, null)
    }

    private val invalidChars = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    private val reservedNames = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) {
            add("COM$i")
            add("LPT$i")
        }
    }
}

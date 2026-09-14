package com.impulsosocial.server.db

/**
 * Splits PostgreSQL scripts into top-level statements without cutting semicolons
 * contained in quoted strings, identifiers, comments or dollar-quoted blocks.
 */
internal object SqlScriptParser {
    fun split(script: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        var inSingleQuote = false
        var inDoubleQuote = false
        var inLineComment = false
        var inBlockComment = false
        var dollarTag: String? = null

        fun flush() {
            val statement = current.toString().trim()
            if (statement.isNotBlank()) statements += statement
            current.setLength(0)
        }

        while (index < script.length) {
            val char = script[index]

            if (inLineComment) {
                current.append(char)
                if (char == '\n') inLineComment = false
                index++
                continue
            }

            if (inBlockComment) {
                if (char == '*' && index + 1 < script.length && script[index + 1] == '/') {
                    current.append("*/")
                    index += 2
                    inBlockComment = false
                } else {
                    current.append(char)
                    index++
                }
                continue
            }

            val activeDollarTag = dollarTag
            if (activeDollarTag != null) {
                if (script.startsWith(activeDollarTag, index)) {
                    current.append(activeDollarTag)
                    index += activeDollarTag.length
                    dollarTag = null
                } else {
                    current.append(char)
                    index++
                }
                continue
            }

            if (inSingleQuote) {
                current.append(char)
                if (char == '\'' && index + 1 < script.length && script[index + 1] == '\'') {
                    current.append(script[index + 1])
                    index += 2
                } else {
                    if (char == '\'') inSingleQuote = false
                    index++
                }
                continue
            }

            if (inDoubleQuote) {
                current.append(char)
                if (char == '"' && index + 1 < script.length && script[index + 1] == '"') {
                    current.append(script[index + 1])
                    index += 2
                } else {
                    if (char == '"') inDoubleQuote = false
                    index++
                }
                continue
            }

            when {
                char == '-' && index + 1 < script.length && script[index + 1] == '-' -> {
                    current.append("--")
                    index += 2
                    inLineComment = true
                }

                char == '/' && index + 1 < script.length && script[index + 1] == '*' -> {
                    current.append("/*")
                    index += 2
                    inBlockComment = true
                }

                char == '\'' -> {
                    current.append(char)
                    inSingleQuote = true
                    index++
                }

                char == '"' -> {
                    current.append(char)
                    inDoubleQuote = true
                    index++
                }

                char == '$' -> {
                    val candidate = readDollarTag(script, index)
                    if (candidate != null) {
                        current.append(candidate)
                        dollarTag = candidate
                        index += candidate.length
                    } else {
                        current.append(char)
                        index++
                    }
                }

                char == ';' -> {
                    flush()
                    index++
                }

                else -> {
                    current.append(char)
                    index++
                }
            }
        }

        check(!inSingleQuote) { "El schema SQL contiene una cadena de texto sin cerrar." }
        check(!inDoubleQuote) { "El schema SQL contiene un identificador entre comillas sin cerrar." }
        check(!inBlockComment) { "El schema SQL contiene un comentario de bloque sin cerrar." }
        check(dollarTag == null) { "El schema SQL contiene un bloque ${dollarTag.orEmpty()} sin cerrar." }

        flush()
        return statements
    }

    private fun readDollarTag(script: String, start: Int): String? {
        if (start + 1 >= script.length) return null
        if (script[start + 1] == '$') return "$$"

        var index = start + 1
        val first = script[index]
        if (!(first == '_' || first.isLetter())) return null
        index++

        while (index < script.length && (script[index] == '_' || script[index].isLetterOrDigit())) {
            index++
        }
        if (index >= script.length || script[index] != '$') return null
        return script.substring(start, index + 1)
    }
}

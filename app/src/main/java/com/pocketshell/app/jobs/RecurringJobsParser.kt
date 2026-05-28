package com.pocketshell.app.jobs

import javax.inject.Inject

/**
 * Parses the fixed-width recurring-jobs table produced by
 * `pocketshell jobs list`. The `pocketshell jobs` CLI forwards verbatim to the
 * underlying scheduler, so the output shape is byte-identical to the legacy
 * `tmuxctl jobs list` table this parser was originally written against.
 */
public class RecurringJobsParser @Inject constructor() {

    public fun parseList(output: String): List<RecurringJob> {
        val lines = output
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return emptyList()

        val headerIndex = lines.indexOfFirst { it.startsWith("ID") && it.contains("SESSION") }
        val rows = if (headerIndex >= 0) lines.drop(headerIndex + 1) else lines
        if (rows.isEmpty()) return emptyList()

        val header = if (headerIndex >= 0) lines[headerIndex] else DEFAULT_HEADER
        val columns = columnsFrom(header)
        return rows.mapNotNull { parseRow(it, columns) }
    }

    private fun parseRow(row: String, columns: List<Column>): RecurringJob? {
        val fields = columns.associate { column ->
            column.name to row.sliceColumn(column).trim()
        }
        val fixedWidth = parseFixedWidthFields(row, columns, fields)
        return fixedWidth ?: parseSplitRow(row)
    }

    private fun parseFixedWidthFields(row: String, columns: List<Column>, fields: Map<String, String>): RecurringJob? {
        return fields["ID"]?.toIntOrNull()?.let { id ->
            val source = when (fields["SOURCE"]?.lowercase()) {
                "inline" -> RecurringJobSource.Inline
                "file" -> RecurringJobSource.File
                else -> return null
            }
            val sessionName = fields["SESSION"].orEmpty().takeIf { it.isNotBlank() } ?: return null
            val every = fields["EVERY"].orEmpty().takeIf { it.isNotBlank() && !it.contains(Regex("\\s")) }
                ?: return null
            val enterDelayMs = fields["DELAY"]?.toIntOrNull() ?: return null
            val (nextRun, detail) = parseNextRunAndDetail(row, columns)
            RecurringJob(
                id = id,
                enabled = fields["ENABLED"].toBooleanFlag(),
                sessionName = sessionName,
                every = every,
                enterDelayMs = enterDelayMs,
                source = source,
                nextRun = nextRun,
                detail = detail,
            )
        }
    }

    private fun parseNextRunAndDetail(row: String, columns: List<Column>): Pair<String, String> {
        val start = columns.firstOrNull { it.name == "NEXT RUN" }?.start ?: return "" to ""
        if (start >= row.length) return "" to ""
        val parts = row.substring(start).trim().split(Regex("\\s+"), limit = 3)
        val nextRun = when {
            parts.size >= 2 -> "${parts[0]} ${parts[1]}"
            parts.isNotEmpty() -> parts[0]
            else -> ""
        }
        val detail = parts.getOrElse(2) { "" }
        return nextRun to detail
    }

    private fun parseSplitRow(row: String): RecurringJob? {
        val parts = row.trim().split(Regex("\\s+"), limit = 9)
        if (parts.size < 8) return null
        val id = parts[0].toIntOrNull() ?: return null
        val enterDelayMs = parts[4].toIntOrNull() ?: return null
        val source = when (parts[5].lowercase()) {
            "inline" -> RecurringJobSource.Inline
            "file" -> RecurringJobSource.File
            else -> return null
        }
        val nextRun = if (parts.size >= 8) "${parts[6]} ${parts[7]}" else parts.getOrElse(6) { "" }
        val detail = parts.getOrElse(8) { "" }
        return RecurringJob(
            id = id,
            enabled = parts[1].toBooleanFlag(),
            sessionName = parts[2],
            every = parts[3],
            enterDelayMs = enterDelayMs,
            source = source,
            nextRun = nextRun,
            detail = detail,
        )
    }

    private fun columnsFrom(header: String): List<Column> {
        val names = HEADER_NAMES.mapNotNull { name ->
            val start = header.indexOf(name)
            if (start >= 0) name to start else null
        }.sortedBy { it.second }
        return names.mapIndexed { index, (name, start) ->
            val end = names.getOrNull(index + 1)?.second ?: Int.MAX_VALUE
            Column(name = name, start = start, end = end)
        }
    }

    private fun String.sliceColumn(column: Column): String {
        if (column.start >= length) return ""
        val end = column.end.coerceAtMost(length)
        return substring(column.start, end)
    }

    private fun String?.toBooleanFlag(): Boolean =
        this?.trim()?.lowercase() in setOf("yes", "true", "1", "enabled")

    private data class Column(
        val name: String,
        val start: Int,
        val end: Int,
    )

    private companion object {
        const val DEFAULT_HEADER = "ID  ENABLED  SESSION  EVERY  DELAY  SOURCE  NEXT RUN             DETAIL"
        val HEADER_NAMES = listOf("ID", "ENABLED", "SESSION", "EVERY", "DELAY", "SOURCE", "NEXT RUN", "DETAIL")
    }
}

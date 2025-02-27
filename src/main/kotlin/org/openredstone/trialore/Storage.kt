package org.openredstone.trialore

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

object Note : Table("note") {
    val id = integer("id").autoIncrement()
    val trial_id = integer("trial_id")
        .index().references(Trial.id)
    val value = text("value")
    override val primaryKey = PrimaryKey(id)
}

object Trial : Table("trial") {
    val id = integer("id").autoIncrement()
    val trialer = varchar("trialer", 36).index()
    val testificate = varchar("testificate", 36).index()
    val app = text("app").nullable()
    val start = integer("start")
    val end = integer("end").nullable()
    val passed = bool("passed").nullable()
    override val primaryKey = PrimaryKey(id)
}

object UsernameCache : Table("username_cache") {
    val uuid = varchar("cache_user", 36).uniqueIndex()
    val username = varchar("cache_username", 16).index()
    override val primaryKey = PrimaryKey(uuid)
}

data class TrialInfo(
    val trialer: UUID,
    val testificate: UUID,
    val app: String,
    val start: Int,
    val end: Int,
    val notes: List<String>,
    val passed: Boolean,
    val attempt: Int = 0
)

fun now() = System.currentTimeMillis().floorDiv(1000).toInt()

class Storage(
    dbFile: String
) {
    val database = Database.connect("jdbc:sqlite:${dbFile}", "org.sqlite.JDBC")
    var uuidToUsernameCache = mapOf<UUID, String>()
    var usernameToUuidCache = mapOf<String, UUID>()

    init {
        initTables()
    }

    private fun initTables() = transaction(database) {
        SchemaUtils.create(
            Note, Trial, UsernameCache
        )
    }

    fun insertTrial(trialer: UUID, testificate: UUID, app: String): Int = transaction(database) {
        Trial.insert {
            it[Trial.trialer] = trialer.toString()
            it[Trial.testificate] = testificate.toString()
            it[Trial.app] = app
            it[start] = now()
        }[Trial.id]
    }

    fun endTrial(trialId: Int, passed: Boolean) = transaction(database) {
        Trial.update({ Trial.id eq trialId}) {
            it[end] = now()
            it[Trial.passed] = passed
        }
    }

    fun getTrials(testificate: UUID): List<Int> = transaction(database) {
        Trial.selectAll().where {
            Trial.testificate eq testificate.toString()
        }.map {
            it[Trial.id]
        }
    }

    fun getTrialInfo(trialId: Int): TrialInfo = transaction(database) {
        val notes = Note.selectAll().where {
            Note.trial_id eq trialId
        }.map { it[Note.value] }
        val resultRow = Trial.selectAll().where {
            Trial.id eq trialId
        }.firstOrNull()
        TrialInfo(
            UUID.fromString(resultRow!![Trial.trialer]),
            UUID.fromString(resultRow[Trial.testificate]),
            resultRow[Trial.app] ?: "No app in database. This is likely a bug",
            resultRow[Trial.start],
            resultRow[Trial.end] ?: 0,
            notes,
            resultRow[Trial.passed] ?: false
        )
    }

    fun getTrialCount(testificate: UUID): Int = transaction(database) {
        Trial.selectAll().where {
            Trial.testificate eq testificate.toString()
        }.count().toInt()
    }

    fun insertNote(trialId: Int, note: String) = transaction(database) {
        Note.insert {
            it[trial_id] = trialId
            it[value] = note
        }
    }

    fun updateNote(noteId: Int, note: String) = transaction(database) {
        Note.update({ Note.id eq noteId }) {
            it[value] = note
        }
    }

    fun deleteNote(noteId: Int) = transaction(database) {
        Note.deleteWhere { id eq noteId }
    }

    fun getNotes(trialId: Int): Map<Int, String> = transaction(database) {
        return@transaction Note.selectAll().where {
            Note.trial_id eq trialId
        }.associate {
            it[Note.id] to it[Note.value]
        }
    }

    fun ensureCachedUsername(user: UUID, username: String) = transaction(database) {
        UsernameCache.upsert {
            it[this.uuid] = user.toString()
            it[this.username] = username
        }
        updateLocalUsernameCache()
    }

    private fun updateLocalUsernameCache() {
        usernameToUuidCache = transaction(database) {
            UsernameCache.selectAll().associate {
                it[UsernameCache.username] to UUID.fromString(it[UsernameCache.uuid])
            }
        }
        uuidToUsernameCache = usernameToUuidCache.entries.associate{(k,v)-> v to k}
    }
}

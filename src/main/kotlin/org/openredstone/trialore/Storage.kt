package org.openredstone.trialore

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
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
    val start: Instant,
    val end: Instant,
    val notes: List<String>,
    val passed: Boolean,
    val attempt: Int,
)

class Storage(
    dbFile: String,
) {
    val database = Database.connect("jdbc:sqlite:$dbFile", "org.sqlite.JDBC")
    var uuidToUsernameCache = mapOf<UUID, String>()
    var usernameToUuidCache = mapOf<String, UUID>()

    init {
        initTables()
    }

    private fun initTables() = transaction(database) {
        SchemaUtils.create(
            Note, Trial, UsernameCache,
        )
    }

    fun insertTrial(trialer: UUID, testificate: UUID, app: String): Int = transaction(database) {
        Trial.insert {
            it[Trial.trialer] = trialer.toString()
            it[Trial.testificate] = testificate.toString()
            it[Trial.app] = app
            it[start] = Instant.now().epochSecond.toInt()
        }[Trial.id]
    }

    fun endTrial(trialId: Int, passed: Boolean) = transaction(database) {
        Trial.update({ Trial.id eq trialId }) {
            it[end] = Instant.now().epochSecond.toInt()
            it[Trial.passed] = passed
        }
    }

    fun getTrials(testificate: UUID): List<TrialInfo> = transaction(database) {
        val notes = Trial.innerJoin(Note) { Trial.id eq Note.trial_id }
            .select(Trial.id, Note.value)
            .where { Trial.testificate eq testificate.toString() }
            .orderBy(Note.id)
            .groupBy({ it[Trial.id] }) { it[Note.value] }
        Trial.selectAll()
            .where { Trial.testificate eq testificate.toString() }
            .orderBy(Trial.id)
            .mapIndexed { i, row -> row.toTrialInfo(notes[row[Trial.id]] ?: emptyList(), i + 1) }
    }

    fun getTrialInfo(trialId: Int, attempt: Int): TrialInfo = transaction(database) {
        val notes = Note.selectAll()
            .where { Note.trial_id eq trialId }
            .orderBy(Note.id)
            .map { it[Note.value] }
        Trial.selectAll()
            .where { Trial.id eq trialId }
            .first()
            .toTrialInfo(notes, attempt)
    }

    private fun ResultRow.toTrialInfo(notes: List<String>, attempt: Int) = TrialInfo(
        trialer = UUID.fromString(this[Trial.trialer]),
        testificate = UUID.fromString(this[Trial.testificate]),
        app = this[Trial.app] ?: "No app in database. This is a bug.",
        start = Instant.ofEpochSecond(this[Trial.start].toLong()),
        end = Instant.ofEpochSecond((this[Trial.end] ?: 0).toLong()),
        notes = notes,
        passed = this[Trial.passed] ?: false,
        attempt = attempt,
    )

    fun getTrialCount(testificate: UUID): Int = transaction(database) {
        Trial.selectAll()
            .where { Trial.testificate eq testificate.toString() }
            .count().toInt()
    }

    fun insertNote(trialId: Int, note: String) = transaction(database) {
        Note.insert {
            it[trial_id] = trialId
            it[value] = note
        }
    }

    /** Returns true if successful, false if the note doesn't exist */
    fun updateNote(trialId: Int, noteId: Int, note: String) = transaction(database) {
        Note.update({ (Note.trial_id eq trialId) and (Note.id eq noteId) }) {
            it[value] = note
        } == 1 // 1 row changed if successful
    }

    /** Returns the deleted note or null if the note doesn't exist */
    fun deleteNote(trialId: Int, noteId: Int) = transaction(database) {
        Note.deleteReturning { (Note.trial_id eq trialId) and (Note.id eq noteId) }
            .map { it[Note.value] }
            .singleOrNull()
    }

    fun getNotes(trialId: Int): Map<Int, String> = transaction(database) {
        Note.selectAll()
            .where { Note.trial_id eq trialId }
            .orderBy(Note.id)
            // associate preserves iteration order
            .associate { it[Note.id] to it[Note.value] }
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
        uuidToUsernameCache = usernameToUuidCache.entries.associate { (k, v) -> v to k }
    }
}

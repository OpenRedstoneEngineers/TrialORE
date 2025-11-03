package org.openredstone.trialore

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
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

object Test : Table("test") {
    val id = integer("id").autoIncrement()
    val testificate = varchar("testificate", 36).index()
    val start = integer("start")
    val end = integer("end").nullable()
    val passed = bool("passed")
    val wrong = integer("wrong")
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

data class TestInfo(
    val testificate: UUID,
    val start: Int,
    val end: Int,
    val passed: Boolean,
    val wrong: Int,
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
        SchemaUtils.createMissingTablesAndColumns(
            Note, Trial, UsernameCache, Test
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

    fun endTest(testificate: UUID, startingtime: Int, passed: Boolean, wrong: Int) = transaction(database) {
        Test.insert {
            it[Test.testificate] = testificate.toString()
            it[start] = startingtime
            it[Test.passed] = passed
            it[Test.wrong] = wrong
            it[end] = now()
        }[Test.id]
    }

    fun getTrials(testificate: UUID): List<TrialInfo> = transaction(database) {
        val notes = Trial.innerJoin(Note) { Trial.id eq Note.trial_id }
            .select(Trial.id, Note.value)
            .where { Trial.testificate eq testificate.toString() }
            .groupBy({ it[Trial.id] }) { it[Note.value] }
        Trial.selectAll()
            .where { Trial.testificate eq testificate.toString() }
            .map { it.toTrialInfo(notes[it[Trial.id]] ?: emptyList()) }
    }

    fun getTests(testificate: UUID): List<TestInfo> = transaction(database) {
        Test.selectAll()
            .where { Test.testificate eq testificate.toString() }
            .map(ResultRow::toTestInfo)
    }

    fun getTrialInfo(trialId: Int): TrialInfo = transaction(database) {
        val notes = Note.selectAll()
            .where { Note.trial_id eq trialId }
            .map { it[Note.value] }
        Trial.selectAll()
            .where { Trial.id eq trialId }
            .first()
            .toTrialInfo(notes)
    }

    private fun ResultRow.toTrialInfo(notes: List<String>) = TrialInfo(
        trialer = UUID.fromString(this[Trial.trialer]),
        testificate = UUID.fromString(this[Trial.testificate]),
        app = this[Trial.app] ?: "No app in database. This is a bug.",
        start = this[Trial.start],
        end = this[Trial.end] ?: 0,
        notes = notes,
        passed = this[Trial.passed] ?: false,
    )

    fun getTestInfo(testId: Int): TestInfo? = transaction(database) {
        Test.selectAll()
            .where { Test.id eq testId }
            .firstOrNull()
            ?.toTestInfo()
    }

    fun getTrialCount(testificate: UUID): Int = transaction(database) {
        Trial.selectAll()
            .where { Trial.testificate eq testificate.toString() }
            .count().toInt()
    }

    fun getTestCount(testificate: UUID): Int = transaction(database) {
        Test.selectAll()
            .where { Test.testificate eq testificate.toString() }
            .count().toInt()
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
        Note.selectAll()
            .where { Note.trial_id eq trialId }
            .associate { it[Note.id] to it[Note.value] }
    }

    fun hasPassedTheTest(testificate: UUID) : Boolean = transaction(database) {
        Test.select(Test.passed)
            .where { (Test.testificate eq testificate.toString()) and (Test.passed eq true) }
            .limit(1)
            .empty().not()
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

private fun ResultRow.toTestInfo() = TestInfo(
    testificate = UUID.fromString(this[Test.testificate]),
    start = this[Test.start],
    end = this[Test.end] ?: 0,
    passed = this[Test.passed],
    wrong = this[Test.wrong],
)

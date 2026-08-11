package org.openredstone.trialore

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

fun getDate(timestamp: Instant) = LocalDateTime.ofInstant(timestamp, ZoneOffset.UTC)

fun Instant.toRelativeTimestamp(): String {
    val difference = ChronoUnit.MINUTES.between(this, Instant.now())
    return when {
        difference < 0 -> "in the future :o"
        difference < 1 -> "just now"
        difference < 2 -> "a minute ago"
        difference < 60 -> "$difference minutes ago"
        difference < 120 -> "an hour ago"
        difference < 1440 -> "${difference / 60} hours ago"
        difference < 2880 -> "a day ago"
        else -> "${difference / 1440} days ago"
    }
}

fun Duration.dayHourMin(): String = "${toDays()}d ${toHoursPart()}h ${toMinutesPart()}m"

@CommandAlias("trial")
@CommandPermission("trialore.trial")
class TrialCommand(
    private val trialORE: TrialOre,
    private val version: String,
) : BaseCommand() {

    @Default
    @Subcommand("info")
    @Description("Information about a TrialORE")
    fun onInfo(player: Player) {
        player.sendInfoMM("Current TrialORE version: <gray>$version")
        player.sendInfoMM(
            "For more details on commands, " +
                "<aqua><click:open_url:'https://github.com/OpenRedstoneEngineers/TrialORE/blob/main/README.md'>" +
                "<hover:show_text:'Go to README'>view the README</hover></click>",
        )
    }

    @Subcommand("history")
    @Description("Get the info of an individual from past trials")
    fun onHistory(player: Player, testificate: User) {
        val trials = trialORE.database.getTrials(testificate.uuid)
        player.sendInfoMM("<gray>${testificate.name} has been in ${trials.size} trials")
        for (trialInfo in trials) {
            val state = if (trialInfo.passed) {
                "<green>Passed</green>"
            } else {
                "<red>Failed</red>"
            }
            val timestamp = trialInfo.start.toRelativeTimestamp()
            val trialer = trialORE.database.uuidToUsernameCache[trialInfo.trialer] ?: "Invalid UUID??"
            player.sendInfoMM(
                "<hover:show_text:'At <gray>${getDate(trialInfo.start)}<white>" +
                    " by <gray>$trialer<white> (State: $state)'><gray>Trial ${trialInfo.attempt}, $timestamp</hover>:",
            )
            if (trialInfo.notes.isEmpty()) {
                player.sendInfoMM("<i>No notes")
            }
            trialInfo.notes.forEach { note ->
                player.sendInfo(note)
            }
        }
    }

    @CommandAlias("trialstart")
    @Subcommand("start")
    @Description("Start a trial")
    @CommandCompletion("@players app")
    fun onStart(player: Player, @Flags("other") testificate: Player, @Single app: String, @Optional @Single force: String?) {
        if (player.uniqueId in trialORE.trialMapping) {
            throw TrialOreException("You are already in the act of trialing")
        }
        if (trialORE.trialMapping.any { (_, meta) -> meta.testificate == testificate.uniqueId }) {
            throw TrialOreException("That individual is already trialing")
        }
        if (trialORE.getParent(testificate.uniqueId) != trialORE.config.studentGroup) {
            throw TrialOreException("That individual is ineligible for trial due to rank")
        }
        if (player.uniqueId == testificate.uniqueId) {
            throw TrialOreException("You cannot trial yourself")
        }
        if (!app.startsWith("https://discourse.openredstone.org/")) {
            throw TrialOreException("Invalid app: $app")
        }

        if (force != "--force") {
            enforceRateLimits(testificate.uniqueId)
        } else {
            player.sendInfo("--force passed, starting trial regardless of rate limits.")
        }

        player.sendInfo("Starting trial of ${testificate.name}")
        testificate.sendInfo("Starting trial with ${player.name}")
        val trialId = trialORE.startTrial(player.uniqueId, testificate.uniqueId, app)
        trialORE.database.insertNote(trialId, "Trial started with --force, rate limits not enforced.")
    }

    fun enforceRateLimits(testificate: UUID) {
        val trials = trialORE.database.getTrials(testificate)
        val primaryCooldownEndsAt = rateLimitEndsAt(trials, 1, Duration.ofDays(1))
        val fails = trials.count { !it.passed }
        val (attempts, perDuration) =
            if (fails >= 3) 1 to Duration.ofDays(31) else 2 to Duration.ofDays(7)
        val secondaryCooldownEndsAt = rateLimitEndsAt(trials, attempts, perDuration)
        val cooldownEndsAt = maxOf(primaryCooldownEndsAt, secondaryCooldownEndsAt)
        val now = Instant.now()
        if (cooldownEndsAt > now) {
            val diff = Duration.between(now, cooldownEndsAt)
            val then = "<hover:show_text:'At <gray>${getDate(cooldownEndsAt)}'>in ${diff.dayHourMin()}</hover>"
            throw TrialOreException("That individual is currently rate limited and can trial again $then".render())
        }
    }

    fun rateLimitEndsAt(trials: List<TrialInfo>, attempts: Int, perDuration: Duration): Instant {
        // we only need to look at the attempts-th-last trial, if it exists, since the trials are in chronological order
        val trial = trials.getOrNull(trials.size - attempts) ?: return Instant.MIN
        return trial.start + perDuration
    }

    @Subcommand("note")
    @Description("Manage notes")
    inner class Note : BaseCommand() {

        @Subcommand("add")
        @CommandAlias("trialnote")
        @Description("Add a note to an active trial")
        fun onNote(player: Player, trialMeta: TrialMeta, note: String) {
            trialORE.database.insertNote(trialMeta.trialId, note.trim())
            player.sendInfoMM("Saving note <gray>\"$note\"")
        }

        @Subcommand("list")
        @CommandAlias("trialnotes")
        @Description("List all current notes")
        fun onList(player: Player, trialMeta: TrialMeta) {
            val notes = trialORE.database.getNotes(trialMeta.trialId)
            if (notes.isEmpty()) {
                player.sendInfoMM("No notes")
                return
            }
            player.sendInfoMM("Current notes:")
            for ((key, value) in notes) {
                val cleanedNote = value
                    .replace("\'", "\\\'")
                    .replace("\"", "\\\"")
                player.sendInfoMM(
                    "<click:suggest_command:'/trial note edit $key ${cleanedNote}'>" +
                        "<hover:show_text:'Edit note'> <yellow>✏</hover></click><gray> |" +
                        "<click:suggest_command:'/trial note remove $key'>" +
                        "<hover:show_text:'Remove note'> <red>✖</hover></click><gray> : <white>" +
                        value,
                )
            }
        }

        @Subcommand("edit")
        @Description("Edit note")
        fun onEdit(player: Player, trialMeta: TrialMeta, noteId: Int, note: String) {
            // need to pass trialId here and in onRemove to protect other trials' notes
            if (!trialORE.database.updateNote(trialMeta.trialId, noteId, note))
                throw TrialOreException("Invalid note id $noteId")
            player.sendInfoMM("Updated note <gray>$note</gray>")
        }

        @Subcommand("remove")
        @Description("Remove a note")
        fun onRemove(player: Player, trialMeta: TrialMeta, noteId: Int) {
            val note = trialORE.database.deleteNote(trialMeta.trialId, noteId)
                ?: throw TrialOreException("Invalid note id $noteId")
            player.sendInfoMM("Removed <gray>$note")
        }
    }

    @Subcommand("finish")
    @Description("Finish a trial")
    inner class Finish : BaseCommand() {

        @CommandAlias("trialpass")
        @Subcommand("pass")
        @Description("Accept this testificate's trial")
        fun onPass(player: Player, trialMeta: TrialMeta) {
            player.sendInfo("Testificate has passed their trial")
            player.sendInfo("You may now communicate this pass with the testificate how you like")
            trialORE.endTrial(player.uniqueId, trialMeta.trialId, passed = true)
        }

        @CommandAlias("trialfail")
        @Subcommand("fail")
        @Description("Fail this testificate's trial")
        fun onFail(player: Player, trialMeta: TrialMeta) {
            player.sendInfo("Testificate has failed their trial")
            player.sendInfo("You may now communicate this fail with the testificate how you like")
            trialORE.endTrial(player.uniqueId, trialMeta.trialId, passed = false)
        }
    }
}

class TrialOreException(override val message: String, val component: Component) : Exception(message) {
    constructor(message: String) : this(message, Component.text(message))
    constructor(component: Component) : this(component.toPlainText(), component)
}

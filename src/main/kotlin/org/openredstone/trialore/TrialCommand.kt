package org.openredstone.trialore

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

fun getDate(timestamp: Long) = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC)

fun getRelativeTimestamp(unixTimestamp: Long): String {
    val currentTime = LocalDateTime.now(ZoneOffset.UTC)
    val eventTime = getDate(unixTimestamp)

    val difference = ChronoUnit.MINUTES.between(eventTime, currentTime)

    return when {
        difference < 1 -> "just now"
        difference < 60 -> "$difference minutes ago"
        difference < 120 -> "an hour ago"
        difference < 1440 -> "${difference / 60} hours ago"
        else -> "${difference / 1440} days ago"
    }
}

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
        player.renderMiniMessage("Current TrialORE version: <gray>$version")
        player.renderMiniMessage("For more details on commands, " +
            "<aqua><click:open_url:'https://github.com/OpenRedstoneEngineers/TrialORE/blob/main/README.md'>" +
            "<hover:show_text:'Go to README'>view the README</hover></click>")
    }

    @Subcommand("history")
    @Description("Get the info of an individual from past trials")
    fun onHistory(player: Player, testificate: User) {
        val trials = trialORE.database.getTrials(testificate.uuid)
        player.renderMiniMessage("<gray>${testificate.name} has been in ${trials.size} trials")
        for (trialInfo in trials) {
            val state = if (trialInfo.passed) {
                "<green>Passed</green>"
            } else {
                "<red>Failed</red>"
            }
            val startTime = trialInfo.start.toLong()
            val timestamp = getRelativeTimestamp(startTime)
            val trialer = trialORE.database.uuidToUsernameCache[trialInfo.trialer] ?: "Invalid UUID??"
            player.renderMiniMessage("<hover:show_text:'At <gray>${getDate(startTime)}<white>" +
                " by <gray>$trialer<white> (State: ${state})'><gray>Trial ${trialInfo.attempt}, $timestamp</hover>:")
            if (trialInfo.notes.isEmpty()) {
                player.renderMiniMessage("<i>No notes")
            }
            trialInfo.notes.forEach { note ->
                player.renderMessage(note)
            }
        }
    }

    @CommandAlias("trialstart")
    @Subcommand("start")
    @Description("Start a trial")
    @CommandCompletion("@players app")
    fun onStart(player: Player, @Flags("other") testificate: Player, @Single app: String) {
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
        player.renderMessage("Starting trial of ${testificate.name}")
        testificate.renderMessage("Starting trial with ${player.name}")
        trialORE.startTrial(player.uniqueId, testificate.uniqueId, app)
    }

    @Subcommand("note")
    @Description("Manage notes")
    inner class Note : BaseCommand() {

        @Subcommand("add")
        @CommandAlias("trialnote")
        @Description("Add a note to an active trial")
        fun onNote(player: Player, trialMeta: TrialMeta, note: String) {
            trialORE.database.insertNote(trialMeta.trialId, note.trim())
            player.renderMiniMessage("Saving note <gray>\"$note\"")
        }

        @Subcommand("list")
        @CommandAlias("trialnotes")
        @Description("List all current notes")
        fun onList(player: Player, trialMeta: TrialMeta) {
            val notes = trialORE.database.getNotes(trialMeta.trialId)
            if (notes.isEmpty()) {
                player.renderMiniMessage("No notes")
                return
            }
            player.renderMiniMessage("Current notes:")
            for ((key, value) in notes) {
                val cleanedNote = value
                    .replace("\'", "\\\'")
                    .replace("\"", "\\\"")
                player.renderMiniMessage("<click:suggest_command:'/trial note edit $key ${cleanedNote}'>" +
                    "<hover:show_text:'Edit note'> <yellow>✏</hover></click><gray> |" +
                    "<click:suggest_command:'/trial note remove $key'>" +
                    "<hover:show_text:'Remove note'> <red>✖</hover></click><gray> : <white>" +
                    value
                )
            }
        }

        @Subcommand("edit")
        @Description("Edit note")
        fun onEdit(player: Player, trialMeta: TrialMeta, noteId: Int, note: String) {
            // need to pass trialId here and in onRemove to protect other trials' notes
            if (!trialORE.database.updateNote(trialMeta.trialId, noteId, note))
                throw TrialOreException("Invalid note id $noteId")
            player.renderMiniMessage("Updated note <gray>$note</gray>")
        }

        @Subcommand("remove")
        @Description("Remove a note")
        fun onRemove(player: Player, trialMeta: TrialMeta, noteId: Int) {
            val note = trialORE.database.deleteNote(trialMeta.trialId, noteId) ?: throw TrialOreException("Invalid note id $noteId")
            player.renderMiniMessage("Removed <gray>$note")
        }
    }

    @Subcommand("finish")
    @Description("Finish a trial")
    inner class Finish : BaseCommand() {

        @CommandAlias("trialpass")
        @Subcommand("pass")
        @Description("Accept this testificate's trial")
        fun onPass(player: Player, trialMeta: TrialMeta) {
            player.renderMessage("Testificate has passed their trial")
            player.renderMessage("You may now communicate this pass with the testificate how you like")
            trialORE.endTrial(player.uniqueId, trialMeta.trialId, passed = true)
        }

        @CommandAlias("trialfail")
        @Subcommand("fail")
        @Description("Fail this testificate's trial")
        fun onFail(player: Player, trialMeta: TrialMeta) {
            player.renderMessage("Testificate has failed their trial")
            player.renderMessage("You may now communicate this fail with the testificate how you like")
            trialORE.endTrial(player.uniqueId, trialMeta.trialId, passed = false)
        }
    }
}

class TrialOreException(override val message: String, val component: Component) : Exception(message) {
    constructor(message: String) : this(message, Component.text(message))
    constructor(component: Component) : this(component.toPlainText(), component)
}

package org.openredstone.trialore

import co.aikar.commands.BaseCommand
import co.aikar.commands.CommandIssuer
import co.aikar.commands.PaperCommandManager
import co.aikar.commands.RegisteredCommand
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.node.types.InheritanceNode
import org.bukkit.command.CommandSender
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.*
import java.util.logging.Level
import kotlin.jvm.optionals.getOrNull

const val VERSION = "1.1"

const val baseMessage = "<dark_gray>[<gray>TrialORE<dark_gray>]<white> <message>"

fun Audience.renderMessage(value: Component) = sendMessage(
    MiniMessage.miniMessage().deserialize(
        baseMessage,
        Placeholder.component("message", value)
    )
)
fun Audience.renderMessage(value: String) = renderMessage(Component.text(value))
fun Audience.renderMiniMessage(value: String) =
    renderMessage(MiniMessage.miniMessage().deserialize(value))

fun Component.toPlainText(): String = PlainTextComponentSerializer.plainText().serialize(this)

data class TrialOreConfig(
    val studentGroup: String = "student",
    val testificateGroup: String = "testificate",
    val builderGroup: String = "builder",
    val webhook: String = "webhook",
    val abandonForgiveness: Long = 6000
)

data class TrialMeta(
    val testificate: UUID,
    val trialId: Int
)

data class User(val uuid: UUID, val name: String)

fun tryParseUUID(s: String) = try {
    UUID.fromString(s)
} catch (_: IllegalArgumentException) {
    null
}

class TrialOre : JavaPlugin(), Listener {
    lateinit var database: Storage
    lateinit var luckPerms: LuckPerms
    lateinit var config: TrialOreConfig
    val trialMapping: MutableMap<UUID, TrialMeta> = mutableMapOf()
    private val mapper = ObjectMapper(YAMLFactory())
    override fun onEnable() {
        loadConfig()
        database = Storage(dataFolder.resolve("trials.db").toString())
        luckPerms = LuckPermsProvider.get()
        config = loadConfig()
        server.pluginManager.registerEvents(this, this)
        PaperCommandManager(this).apply {
            commandContexts.registerIssuerOnlyContext(TrialMeta::class.java) { context ->
                trialMapping[context.player.uniqueId]
                    ?: throw TrialOreException("You are not trialing anyone")
            }
            commandContexts.registerContext(User::class.java) { context ->
                val arg = context.popFirstArg()
                val uuid = server.getPlayer(arg)?.uniqueId
                    ?: tryParseUUID(arg)
                    ?: database.usernameToUuidCache[arg]
                    ?: throw TrialOreException("Unknown user, please provide a known username or UUID")
                User(uuid, database.uuidToUsernameCache[uuid] ?: uuid.toString())
            }
            commandCompletions.registerCompletion("usernameCache") { database.usernameToUuidCache.keys }
            commandCompletions.setDefaultCompletion("usernameCache", User::class.java)
            registerCommand(TrialCommand(this@TrialOre, VERSION))
            setDefaultExceptionHandler(::handleCommandException, false)
        }
    }

    override fun onDisable() {
        trialMapping.forEach { (trialer, meta) ->
            endTrial(trialer, meta.trialId, false, "This trial was automatically ended as the server went offline")
        }
    }

    private fun loadConfig(): TrialOreConfig {
        if (!dataFolder.exists()) {
            logger.info("No resource directory found, creating directory")
            dataFolder.mkdir()
        }
        val configFile = File(dataFolder, "config.yml")
        // does not overwrite or throw
        configFile.createNewFile()
        val config = mapper.readTree(configFile)
        val loadedConfig = mapper.treeToValue(config, TrialOreConfig::class.java)
        logger.info("Loaded config.yml")
        return loadedConfig
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        database.ensureCachedUsername(event.player.uniqueId, event.player.name)
        trialMapping.forEach { (_, meta) ->
            if (meta.testificate == event.player.uniqueId ) {
                setLpParent(meta.testificate, config.testificateGroup)
            }
        }
    }

    @EventHandler
    fun onLeave(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        trialMapping.forEach { (trialer, meta) ->
            val (testificate, trialId) = meta
            // NOTE: server.getPlayer only returns online players
            if (uuid == testificate) {
                server.getPlayer(trialer)?.renderMessage(
                    "The testificate has left. They have 5 minutes to rejoin before this trial is " +
                        "automatically invalidated"
                )
                setLpParent(testificate, config.studentGroup)
                server.scheduler.runTaskLater(this, Runnable {
                    if (trialMapping[trialer]?.trialId != trialId) {
                        // the trial has already ended
                        return@Runnable
                    }
                    if (server.getPlayer(testificate) != null) {
                        // Testificate has reconnected, don't end
                        return@Runnable
                    }
                    // END TRIAL!!!
                    endTrial(
                        trialer, trialId, false,
                        "The trial was automatically ended due to the trialer or testificate leaving"
                    )
                    server.getPlayer(trialer)?.renderMessage(
                        "The trial was automatically failed as the testificate has left for longer than 5 minutes"
                    )
                }, config.abandonForgiveness)
            }
            if (uuid == trialer) {
                server.getPlayer(testificate)?.renderMessage(
                    "The trialer has left. They have 5 minutes to rejoin before this trial is " +
                        "automatically invalidated"
                )
                server.scheduler.runTaskLater(this, Runnable {
                    if (trialMapping[trialer]?.trialId != trialId) {
                        // the trial has already ended
                        return@Runnable
                    }
                    if (server.getPlayer(trialer) != null) {
                        // Trialer has reconnected, don't end
                        return@Runnable
                    }
                    // END TRIAL!!!
                    endTrial(
                        trialer, trialId, false,
                        "The trial was automatically ended due to the trialer or testificate leaving"
                    )
                    server.getPlayer(testificate)?.renderMessage(
                        "The trial was automatically failed as the trialer has left for longer than 5 minutes"
                    )
                }, config.abandonForgiveness)
            }
        }
    }

    fun startTrial(trialer: UUID, testificate: UUID, app: String) {
        val trialId = database.insertTrial(trialer, testificate, app)
        trialMapping[trialer] = TrialMeta(testificate, trialId)
        setLpParent(testificate, config.testificateGroup)
    }

    fun endTrial(trialer: UUID, trialId: Int, passed: Boolean, finalNote: String? = null) {
        val (testificate, _) = checkNotNull(trialMapping.remove(trialer)) { "endTrial: not taking a trial" }
        if (finalNote != null) {
            database.insertNote(trialId, finalNote)
        }
        database.endTrial(trialId, passed)
        if (passed) {
            setLpParent(testificate, config.builderGroup)
        } else {
            setLpParent(testificate, config.studentGroup)
        }
        sendReport(database.getTrialInfo(trialId, database.getTrialCount(testificate))) // jank
    }

    fun getParent(uuid: UUID): String? = luckPerms.userManager.getUser(uuid)?.primaryGroup

    private fun setLpParent(uuid: UUID, parent: String) {
        luckPerms.userManager.loadUser(uuid).thenCompose { user ->
            val oldNode = InheritanceNode.builder(user.primaryGroup).value(true).build()
            user.data().remove(oldNode)
            val newNode = InheritanceNode.builder(parent).value(true).build()
            user.data().add(newNode)
            user.primaryGroup = parent
            luckPerms.userManager.saveUser(user).thenRun {
                luckPerms.messagingService.getOrNull()?.pushUserUpdate(user)
            }
        }
    }

    private fun sendReport(trialInfo: TrialInfo) {
        val lines = mutableListOf(
            "**Trialer**: ${database.uuidToUsernameCache[trialInfo.trialer]}",
            "**Attempt**: ${trialInfo.attempt}",
            "**Start**: <t:${trialInfo.start.epochSecond}:F>",
            "**End**: <t:${trialInfo.end.epochSecond}:F>",
            "**Notes**:"
        )
        trialInfo.notes.forEach { note ->
            lines.add("* $note")
        }
        val (result, color) = if (trialInfo.passed) {
            "*Passed*" to 0x5fff58
        } else {
            "*Failed*" to 0xff5858
        }
        val payload = mapOf(
            "embeds" to listOf(
                mapOf(
                    "title" to database.uuidToUsernameCache[trialInfo.testificate],
                    "description" to lines.joinToString("\n"),
                    "url" to trialInfo.app,
                    "color" to color,
                    "fields" to listOf(
                        mapOf(
                            "name" to "State",
                            "value" to result
                        )
                    )
                )
            )
        )
        postWebhook(payload)
    }

    private fun postWebhook(payload: Any) {
        val req = HttpRequest.newBuilder(URI(config.webhook))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(ObjectMapper().writeValueAsString(payload)))
            .build()
        val status = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
        if (status != 204) {
            logger.warning("Webhook POST request returned status code $status")
        }
    }

    private fun handleCommandException(
        command: BaseCommand,
        registeredCommand: RegisteredCommand<*>,
        sender: CommandIssuer,
        args: List<String>,
        throwable: Throwable
    ): Boolean {
        val exception = throwable as? TrialOreException ?: run {
            logger.log(Level.SEVERE, "Error while executing command", throwable)
            return false
        }
        sender.getIssuer<CommandSender>().renderMessage(exception.component.color(NamedTextColor.RED))
        return true
    }
}

package net.yakclient.client

import BootLoggerFactory
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenDescriptor
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenRepositorySettings
import com.durganmcbroom.jobs.job
import com.durganmcbroom.jobs.launch
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.path
import dev.extframework.boot.archive.ArchiveGraph
import dev.extframework.boot.archive.ClassLoadedArchiveNode
import dev.extframework.boot.audit.Auditors
import dev.extframework.boot.dependency.DependencyTypeContainer
import dev.extframework.boot.loader.IntegratedLoader
import dev.extframework.boot.loader.SourceProvider
import dev.extframework.boot.maven.MavenLikeResolver
import dev.extframework.common.util.readInputStream
import dev.extframework.common.util.resolve
import dev.extframework.extloader.workflow.*
import dev.extframework.internal.api.extension.artifact.ExtensionDescriptor
import dev.extframework.internal.api.extension.artifact.ExtensionRepositorySettings
import dev.extframework.minecraft.bootstrapper.MinecraftNode
import dev.extframework.minecraft.bootstrapper.MinecraftProviderFinder
import dev.extframework.minecraft.bootstrapper.MinecraftProviderRemoteLookup
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.*

internal class LaunchInfo<T : WorkflowContext>(
    val args: Array<String>,
    val mainClass: String,
    val classloader: ClassLoader,
    val context: T,
    val workflow: Workflow<T>,
    val mappingNS: String
)

internal class GameOptions(
    val workingDir: Path,
    val username: String?,
    val version: String,
    val gameDir: String?,
    val assetsDir: String?,
    val assetIndex: String?,
    val uuid: String?,
    val accessToken: String = "",
    val clientId: String?,
    val xuid: String?,
    val userType: String?,
    val versionType: String?,
    val quickPlayPath: String?
) {
    override fun toString() = listOfNotNull(
        "workingDir=$workingDir",
        username?.let { "username=$it" },
        "version=$version",
        gameDir?.let { "gameDir=$it" },
        assetsDir?.let { "assetsDir=$it" },
        assetIndex?.let { "assetIndex=$it" },
        uuid?.let { "uuid=$it" },
        if (accessToken.isNotEmpty()) "accessToken=$accessToken" else null,
        clientId?.let { "clientId=$it" },
        xuid?.let { "xuid=$it" },
        userType?.let { "userType=$it" },
        versionType?.let { "versionType=$it" },
        quickPlayPath?.let { "quickPlayPath=$it" }
    ).joinToString(", ", "GameOptions[", "]")
}

internal data class LaunchContext(
    val launchInfo: LaunchInfo<*>,
    val options: GameOptions,
    val extensions: List<ExtensionDescriptor>,
    val repositories: List<ExtensionRepositorySettings>,
    val archiveGraph: ArchiveGraph,
    val extraAuditors: Auditors,
    val dependencyTypes: DependencyTypeContainer,
)

internal class ProductionCommand(

) : CliktCommand(
    invokeWithoutSubcommand = true
) {
    val mcProviderRepository by option()
        .repository()
        .default(SimpleMavenRepositorySettings.default(url = "https://maven.extframework.dev/snapshots"))
    val forceProvider by option()
        .mavenDescriptor()
    val workingDir: Path by option("--working-dir", "-d").path(canBeFile = false)
        .convert { it.toAbsolutePath() }
        .default(getHomedir())

    val username by option()
    val version by option()
        .required()

    val gameDir by option("--gameDir")
    val assetsDir by option("--assetsDir")
    val assetIndex by option("--assetIndex")
    val uuid by option("--uuid")
    val accessToken by option("--accessToken").default("")
    val clientId by option("--clientId")
    val xuid by option("--xuid")
    val userType by option("--userType")
    val versionType by option("--versionType")
    val quickPlayPath by option("--quickPlayPath")

    val extensions by option("--extension", "-e")
        .extensionDescriptor()
        .multiple()

    val repositories by option("--repository", "-r")
        .repository()
        .multiple()

    lateinit var launchContext: LaunchContext

    override fun run() = launch(BootLoggerFactory()) {
        println("Beginning Minecraft Launch phase")
        val packagedDependencies: Set<SimpleMavenDescriptor> = parsePackagedDependencies()
        val archiveGraph: ArchiveGraph = setupArchiveGraph(workingDir resolve "archives", packagedDependencies)
        val extraAuditors: Auditors = setupExtraAuditors(packagedDependencies)
        val dependencyTypes: DependencyTypeContainer = setupDependencyTypes(archiveGraph, extraAuditors)

        val handle: MinecraftNode = loadMinecraft(
            version,
            mcProviderRepository,
            workingDir,
            archiveGraph,
            dependencyTypes.get("simple-maven")!!.resolver as MavenLikeResolver<ClassLoadedArchiveNode<SimpleMavenDescriptor>, *>,
            forceProvider
        )().merge()

        val info = LaunchInfo(
            arrayOfNonNulls(
                "--username" to username,
                "--version" to version,
                "--gameDir" to (gameDir ?: handle.runtimeInfo.gameDir.toString()),
                "--assetsDir" to (assetsDir ?: handle.runtimeInfo.assetsPath.toString()),
                "--assetIndex" to (assetIndex ?: handle.runtimeInfo.assetsName),
                "--uuid" to uuid,
                "--accessToken" to accessToken,
                "--clientId" to clientId,
                "--xuid" to xuid,
                "--userType" to userType,
                "--versionType" to versionType,
                "--quickPlayPath" to quickPlayPath,
            ),
            handle.runtimeInfo.mainClass,
            IntegratedLoader(
                name = "Minecraft",
                sourceProvider = object : SourceProvider {
                    override val packages: Set<String> = setOf()

                    override fun findSource(name: String): ByteBuffer? {
                        return handle.resources.findResources(name.replace('.', '/') + ".class")
                            .firstOrNull()
                            ?.openStream()
                            ?.readInputStream()
                            ?.let(ByteBuffer::wrap)
                    }
                },
                resourceProvider = handle.resources,
                parent = ClassLoader.getPlatformClassLoader(),
            ),
            ProdWorkflowContext(
                extensions.zip(repositories).toMap(),
            ),
            ProdWorkflow(),
            "mojang:deobfuscated",
        )

        launchContext = LaunchContext(
            info,
            GameOptions(
                workingDir,
                username,
                version,
                gameDir,
                assetsDir,
                assetIndex,
                uuid,
                accessToken,
                clientId,
                xuid,
                userType,
                versionType,
                quickPlayPath
            ),
            extensions,
            repositories,
            archiveGraph, extraAuditors, dependencyTypes
        )

        println("Launch context built: ${launchContext.options}")
    }
}

private fun loadMinecraft(
    version: String,
    mcProviderRepo: SimpleMavenRepositorySettings,
    path: Path,
    archiveGraph: ArchiveGraph,
    mavenResolver: MavenLikeResolver<ClassLoadedArchiveNode<SimpleMavenDescriptor>, *>,
    forceProvider: SimpleMavenDescriptor?,
) = job {
    val cache = path resolve "minecraft"
    dev.extframework.minecraft.bootstrapper.loadMinecraft(
        version,
        mcProviderRepo,
        cache,
        archiveGraph, mavenResolver,
        if (forceProvider == null) MinecraftProviderRemoteLookup(cache)
        else object : MinecraftProviderFinder {
            override fun find(version: String): SimpleMavenDescriptor {
                return forceProvider
            }
        },
    )().merge()
}

private fun arrayOfNonNulls(
    vararg args: Pair<String, String?>,
): Array<String> = args.filter { it.second != null }.flatMap {
    listOf(it.first, it.second!!)
}.toTypedArray()

private fun NullableOption<String, String>.mavenDescriptor(): NullableOption<SimpleMavenDescriptor, SimpleMavenDescriptor> =
    convert {
        SimpleMavenDescriptor.parseDescription(it) ?: throw BadParameterValue(
            "Incorrectly formatted descriptor, expected a colon seperated 'group:artifact:version', got '$it'",
            this@mavenDescriptor
        )
    }

private fun NullableOption<String, String>.extensionDescriptor(): NullableOption<ExtensionDescriptor, ExtensionDescriptor> =
    convert {
        runCatching { ExtensionDescriptor.parseDescriptor(it) }.getOrNull() ?: throw BadParameterValue(
            "Incorrectly formatted descriptor, expected a colon seperated 'group:artifact:version', got '$it'",
            this@extensionDescriptor
        )
    }

private fun NullableOption<String, String>.repository(): NullableOption<SimpleMavenRepositorySettings, SimpleMavenRepositorySettings> =
    convert { repo ->
        repo.split("@")
            .takeIf { it.size == 2 }
            ?.let { (type, path) ->
                when (type) {
                    "local" -> SimpleMavenRepositorySettings.local(path = path)
                    "default" -> SimpleMavenRepositorySettings.default(url = path)
                    else -> throw BadParameterValue("Repository type '$type' is not supported.", this@repository)
                }
            }
            ?: throw BadParameterValue(
                "Incorrectly formatted maven repository given for CLI arg: 'repository'. Format is : '<local/default>@<URL>'",
                this@repository
            )
    }
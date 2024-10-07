package net.yakclient.client

import com.durganmcbroom.artifact.resolver.ArtifactMetadata
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenDescriptor
import com.durganmcbroom.jobs.Job
import com.durganmcbroom.jobs.job
import dev.extframework.boot.archive.*
import dev.extframework.boot.audit.Auditors
import dev.extframework.boot.audit.chain
import dev.extframework.boot.constraint.ConstraintArchiveAuditor
import dev.extframework.boot.dependency.BasicDependencyNode
import dev.extframework.boot.dependency.DependencyTypeContainer
import dev.extframework.boot.maven.MavenConstraintNegotiator
import dev.extframework.boot.maven.MavenDependencyResolver
import dev.extframework.boot.maven.MavenResolverProvider
import dev.extframework.boot.monad.removeIf
import dev.extframework.common.util.readInputStream
import java.nio.file.Path


internal fun setupExtraAuditors(
    packagedDependencies: Set<SimpleMavenDescriptor>
): Auditors {
    val negotiator = MavenConstraintNegotiator()

    val alreadyLoaded = packagedDependencies.mapTo(HashSet()) {
        negotiator.classify(it)
    }

    val packagedDependencyRemover = object : ArchiveTreeAuditor {
        override fun audit(event: ArchiveTreeAuditContext): Job<ArchiveTreeAuditContext> = job {
           event.copy(tree = event.tree.removeIf {
                alreadyLoaded.contains(negotiator.classify(it.value.descriptor as? SimpleMavenDescriptor ?: return@removeIf false))
            }!!)
        }
    }
    val archiveTreeAuditor = ConstraintArchiveAuditor(
        listOf(MavenConstraintNegotiator()),
    ).chain(packagedDependencyRemover)

    return Auditors(
        archiveTreeAuditor
    )
}

internal fun parsePackagedDependencies(): Set<SimpleMavenDescriptor> {
    val dependencies: java.util.HashSet<SimpleMavenDescriptor> =
        AppTarget::class.java.getResourceAsStream("/dependencies.txt")?.use {
            val fileStr = String(it.readInputStream())
            fileStr.split("\n").toSet()
        }?.filterNot { it.isBlank() }?.mapTo(HashSet()) { SimpleMavenDescriptor.parseDescription(it)!! }
            ?: throw IllegalStateException("Cant load dependencies?")

    return dependencies
}

internal fun setupArchiveGraph(
    path: Path,
    packagedDependencies: Set<SimpleMavenDescriptor>,
): ArchiveGraph {
    val archiveGraph = DefaultArchiveGraph(
        path,
        packagedDependencies.associateByTo(HashMap()) {
            BasicDependencyNode(it, null,
                object : ArchiveAccessTree {
                    override val descriptor: ArtifactMetadata.Descriptor = it
                    override val targets: List<ArchiveTarget> = listOf()
                }
            )
        } as MutableMap<ArtifactMetadata.Descriptor, ArchiveNode<*>>
    )

    return archiveGraph
}

internal fun setupDependencyTypes(
    archiveGraph: ArchiveGraph,
    auditors: Auditors,
): DependencyTypeContainer {
    val maven = object : MavenDependencyResolver(
        parentClassLoader = ClassLoader.getSystemClassLoader(),
    ) {
        override val auditors: Auditors
            get() = auditors
    }

    val dependencyTypes = DependencyTypeContainer(archiveGraph)
    dependencyTypes.register("simple-maven", MavenResolverProvider(maven))

    return dependencyTypes
}

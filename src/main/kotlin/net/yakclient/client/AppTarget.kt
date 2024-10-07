package net.yakclient.client

import com.durganmcbroom.artifact.resolver.ArtifactMetadata
import dev.extframework.archives.ArchiveHandle
import dev.extframework.archives.zip.classLoaderToArchive
import dev.extframework.boot.archive.ArchiveAccessTree
import dev.extframework.boot.archive.ArchiveTarget
import dev.extframework.boot.archive.ClassLoadedArchiveNode
import dev.extframework.boot.loader.*
import dev.extframework.common.util.readInputStream
import dev.extframework.internal.api.target.ApplicationDescriptor
import dev.extframework.internal.api.target.ApplicationTarget
import java.net.URL
import java.nio.ByteBuffer

internal class AppTarget(
    private val delegate: ClassLoader,
    version: String,
) : ApplicationTarget {
    override val node: ClassLoadedArchiveNode<ApplicationDescriptor> =
        object : ClassLoadedArchiveNode<ApplicationDescriptor> {
            private val appDesc = ApplicationDescriptor(
                "net.minecraft",
                "minecraft",
                version,
                "client"
            )
            override val descriptor: ApplicationDescriptor = appDesc
            override val access: ArchiveAccessTree = object : ArchiveAccessTree {
                override val descriptor: ArtifactMetadata.Descriptor = appDesc
                override val targets: List<ArchiveTarget> = listOf()
            }
            override val handle: ArchiveHandle = classLoaderToArchive(
                MutableClassLoader(
                    name = "minecraft-loader",
                    resources = MutableResourceProvider(mutableListOf(
                        object : ResourceProvider {
                            override fun findResources(name: String): Sequence<URL> {
                                return delegate.getResources(name).asSequence()
                            }
                        }
                    )),
                    sources = object : MutableSourceProvider(mutableListOf(
                        object : SourceProvider {
                            override val packages: Set<String> = setOf("*")

                            override fun findSource(name: String): ByteBuffer? {
                                val stream = delegate.getResourceAsStream(name.replace(".", "/") + ".class")
                                    ?: return null

                                val bytes = stream.readInputStream()
                                return ByteBuffer.wrap(bytes)
                            }
                        }
                    )) {
                        override fun findSource(name: String): ByteBuffer? =
                            ((packageMap[name.substring(0, name.lastIndexOf('.').let { if (it == -1) 0 else it })]
                                ?: listOf()) +
                                    (packageMap["*"] ?: listOf())).firstNotNullOfOrNull { it.findSource(name) }
                    },
                    parent = ClassLoader.getPlatformClassLoader(),
                )
            )
        }
}

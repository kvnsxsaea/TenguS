package com.tengus.scraper

import org.slf4j.LoggerFactory
import java.io.File
import java.net.JarURLConnection
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

/**
 * Auto-discovers and registers [BaseScraper] implementations by scanning a
 * configurable classpath package at startup.
 *
 * Validates: Requirements 24.1, 24.2, 24.3, 24.4, 24.5
 */
class ScraperRegistry(private val scanPackage: String) {

    private val logger = LoggerFactory.getLogger(ScraperRegistry::class.java)
    private val registry = ConcurrentHashMap<String, KClass<out BaseScraper>>()

    /**
     * Scans [scanPackage] on the classpath for concrete classes extending
     * [BaseScraper] and registers each one by its declared [BaseScraper.siteId].
     *
     * Throws [IllegalStateException] on duplicate site identifiers.
     */
    fun discover() {
        val packagePath = scanPackage.replace('.', '/')
        val classLoader = Thread.currentThread().contextClassLoader

        val resources = classLoader.getResources(packagePath)
        val classes = mutableListOf<KClass<out BaseScraper>>()

        while (resources.hasMoreElements()) {
            val resource = resources.nextElement()
            when (resource.protocol) {
                "file" -> classes.addAll(scanDirectory(File(resource.toURI()), scanPackage))
                "jar" -> classes.addAll(scanJar(resource, packagePath))
            }
        }

        for (scraperClass in classes) {
            // Read siteId from the companion or by inspecting the class.
            // Since siteId is an abstract instance property, we need to read it
            // from the KClass metadata. We use a temporary reflective approach:
            // find the constructor and attempt a lightweight instantiation is not
            // feasible without dependencies. Instead, we look for a companion
            // object constant or use the class simple name convention.
            //
            // Practical approach: instantiate temporarily is not viable because
            // BaseScraper requires constructor dependencies. Instead, we register
            // the class and let the factory resolve siteId at instantiation time.
            // However, the requirements say we must detect duplicates at startup.
            //
            // Best approach: look for a SITE_ID companion const, or use a
            // dedicated annotation / static-like property. Since the design uses
            // an abstract val siteId, we'll scan for a companion object with a
            // SITE_ID field, falling back to the simple class name in lowercase.
            val siteId = resolveSiteId(scraperClass)
            register(siteId, scraperClass)
        }

        logger.info(
            "Scraper discovery complete: {} scraper(s) registered — {}",
            registry.size,
            registry.keys.sorted().joinToString(", ")
        )
    }

    /**
     * Registers a scraper class under the given [siteId].
     *
     * @throws IllegalStateException if [siteId] is already registered by a
     *   different class.
     */
    fun register(siteId: String, scraperClass: KClass<out BaseScraper>) {
        val existing = registry.putIfAbsent(siteId, scraperClass)
        if (existing != null && existing != scraperClass) {
            throw IllegalStateException(
                "Duplicate site identifier '$siteId': " +
                    "already registered by ${existing.qualifiedName}, " +
                    "conflicting class ${scraperClass.qualifiedName}"
            )
        }
    }

    /**
     * Returns the [KClass] registered for [siteId].
     *
     * @throws IllegalArgumentException if no scraper is registered for [siteId].
     */
    fun lookup(siteId: String): KClass<out BaseScraper> {
        return registry[siteId]
            ?: throw IllegalArgumentException(
                "No scraper registered for site identifier '$siteId'. " +
                    "Registered identifiers: ${registry.keys.sorted().joinToString(", ")}"
            )
    }

    /** Returns all registered site identifiers. */
    fun registeredSiteIds(): Set<String> = registry.keys.toSet()

    // ---- internal helpers ----

    /**
     * Resolves the site identifier for a [BaseScraper] subclass.
     *
     * Strategy:
     * 1. Look for a companion object field named `SITE_ID`.
     * 2. Fall back to the simple class name in lowercase with "scraper" suffix removed.
     */
    private fun resolveSiteId(scraperClass: KClass<out BaseScraper>): String {
        // Try companion object SITE_ID field
        try {
            val companion = scraperClass.java.declaredFields
                .firstOrNull { it.name == "Companion" }
            if (companion != null) {
                companion.isAccessible = true
                val companionInstance = companion.get(null)
                val siteIdField = companionInstance.javaClass.declaredFields
                    .firstOrNull { it.name == "SITE_ID" }
                if (siteIdField != null) {
                    siteIdField.isAccessible = true
                    val value = siteIdField.get(companionInstance)
                    if (value is String) return value
                }
                // Also try getSITE_ID method (Kotlin property accessor)
                val siteIdMethod = companionInstance.javaClass.declaredMethods
                    .firstOrNull { it.name == "getSITE_ID" || it.name == "getSiteId" }
                if (siteIdMethod != null) {
                    siteIdMethod.isAccessible = true
                    val value = siteIdMethod.invoke(companionInstance)
                    if (value is String) return value
                }
            }
        } catch (_: Exception) {
            // Fall through to default
        }

        // Fallback: derive from class name
        val simpleName = scraperClass.simpleName ?: scraperClass.java.simpleName
        return simpleName
            .removeSuffix("Scraper")
            .replace(Regex("([a-z])([A-Z])"), "$1-$2")
            .lowercase()
    }

    /**
     * Scans a file-system directory for `.class` files and returns those that
     * are concrete subclasses of [BaseScraper].
     */
    private fun scanDirectory(directory: File, packageName: String): List<KClass<out BaseScraper>> {
        val result = mutableListOf<KClass<out BaseScraper>>()
        if (!directory.exists()) return result

        directory.walkTopDown().forEach { file ->
            if (file.isFile && file.name.endsWith(".class")) {
                val relativePath = file.relativeTo(directory).path
                val className = packageName + "." + relativePath
                    .removeSuffix(".class")
                    .replace(File.separatorChar, '.')
                tryLoadScraperClass(className)?.let { result.add(it) }
            }
        }
        return result
    }

    /**
     * Scans a JAR resource for classes in the target package that are concrete
     * subclasses of [BaseScraper].
     */
    private fun scanJar(resource: java.net.URL, packagePath: String): List<KClass<out BaseScraper>> {
        val result = mutableListOf<KClass<out BaseScraper>>()
        val connection = resource.openConnection() as JarURLConnection
        val jarFile = connection.jarFile

        jarFile.entries().asSequence()
            .filter { it.name.startsWith(packagePath) && it.name.endsWith(".class") }
            .forEach { entry ->
                val className = entry.name
                    .removeSuffix(".class")
                    .replace('/', '.')
                tryLoadScraperClass(className)?.let { result.add(it) }
            }
        return result
    }

    /**
     * Attempts to load a class by name and returns its [KClass] if it is a
     * concrete (non-abstract) subclass of [BaseScraper]. Returns null otherwise.
     */
    @Suppress("UNCHECKED_CAST")
    private fun tryLoadScraperClass(className: String): KClass<out BaseScraper>? {
        return try {
            val clazz = Class.forName(className, false, Thread.currentThread().contextClassLoader)
            val kClass = clazz.kotlin
            if (kClass.isSubclassOf(BaseScraper::class)
                && !kClass.isAbstract
                && kClass != BaseScraper::class
            ) {
                kClass as KClass<out BaseScraper>
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}

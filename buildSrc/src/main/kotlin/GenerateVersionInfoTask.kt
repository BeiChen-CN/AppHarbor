import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GenerateVersionInfoTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val appName: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            // 由 :app:shared:generateVersionInfo 生成，请勿手动修改。
            package misc

            object VersionInfo {
                const val VERSION_NAME = "${versionName.get()}"
                const val VERSION_CODE = ${versionCode.get()}
                const val APP_NAME = "${appName.get()}"
                const val PACKAGE_NAME = "${packageName.get()}"
            }
            """.trimIndent(),
        )
    }
}

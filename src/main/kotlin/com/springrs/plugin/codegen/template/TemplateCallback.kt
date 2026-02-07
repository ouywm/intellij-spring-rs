package com.springrs.plugin.codegen.template

/**
 * Callback object exposed as `$callback` in Velocity templates.
 *
 * Allows templates to control output file behavior:
 * - File name and save path
 * - Whether to write the file at all
 * - Whether to reformat the code after generation
 *
 * Aligned with EasyCode's `$callback` API.
 *
 * Usage in templates:
 * ```velocity
 * $!callback.setFileName("${moduleName}_mapper.rs")
 * $!callback.setSavePath("src/mapper")
 * $!callback.setReformat(true)
 * ## Conditionally skip file generation:
 * #if($columns.size() == 0)
 * $!callback.setWriteFile(false)
 * #end
 * ```
 */
class TemplateCallback {

    /** Output file name. If set by template, overrides the generator's default. */
    var fileName: String? = null
        private set

    /** Output directory (relative to project root). If set, overrides the generator's default. */
    var savePath: String? = null
        private set

    /** Whether to write the file. Templates can set to `false` to skip generation. */
    var writeFile: Boolean = true
        private set

    /** Whether to run code formatter after generation. */
    var reformat: Boolean = false
        private set

    // ── Setter methods (called from Velocity templates via $callback.setXxx) ──

    fun setFileName(name: String) { this.fileName = name }

    fun setSavePath(path: String) { this.savePath = path }

    fun setWriteFile(write: Boolean) { this.writeFile = write }

    fun setReformat(reformat: Boolean) { this.reformat = reformat }

    /** Reset to defaults (called between template renders). */
    fun reset() {
        fileName = null
        savePath = null
        writeFile = true
        reformat = false
    }
}

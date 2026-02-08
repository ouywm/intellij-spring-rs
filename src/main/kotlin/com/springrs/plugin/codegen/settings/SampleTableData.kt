package com.springrs.plugin.codegen.settings

import com.springrs.plugin.codegen.ColumnInfo
import com.springrs.plugin.codegen.TableInfo
import com.springrs.plugin.codegen.VelocityTemplateEngine
import com.springrs.plugin.codegen.CodeGenSettingsState
import com.springrs.plugin.codegen.RelationInfo
import com.springrs.plugin.codegen.RelationType

/**
 * Generates sample/mock table data for template live preview.
 *
 * Creates a realistic "users" table with common column types,
 * so template authors can see how their templates render.
 *
 * Reuses [VelocityTemplateEngine.buildTableContext] to guarantee the preview
 * context has exactly the same keys as real code generation.
 */
object SampleTableData {

    /**
     * Build a complete Velocity context using sample data.
     *
     * Delegates to [VelocityTemplateEngine.buildTableContext] (single source of truth),
     * then adds template-specific derive variables needed for preview rendering.
     */
    fun buildContext(): Map<String, Any?> {
        val settings = CodeGenSettingsState()  // default values (not project-bound)
        val context = VelocityTemplateEngine.buildTableContext(
            sampleTable(), settings, sampleRelations()
        )

        // Add derive-specific vars so all 5 templates render correctly in preview
        context["baseDerives"] = "Clone, Debug, PartialEq, Eq, DeriveEntityModel"
        context["extraDerives"] = "Serialize, Deserialize"
        context["dtoBaseDerives"] = "Debug, Deserialize"
        context["voBaseDerives"] = "Debug, Serialize"
        context["addSerde"] = true
        context["addBon"] = false
        context["addJsonSchema"] = false
        context["addValidate"] = false
        context["needsDecimal"] = false

        return context
    }

    /**
     * Sample "users" table with common column types.
     */
    private fun sampleTable(): TableInfo = TableInfo(
        name = "users",
        comment = "System users table",
        columns = listOf(
            ColumnInfo("id", "serial", "i32", isPrimaryKey = true, isNullable = false, isAutoIncrement = true, comment = "Primary key", defaultValue = null),
            ColumnInfo("username", "varchar(50)", "String", isPrimaryKey = false, isNullable = false, isAutoIncrement = false, comment = "Login username", defaultValue = null),
            ColumnInfo("email", "varchar(100)", "String", isPrimaryKey = false, isNullable = false, isAutoIncrement = false, comment = "Email address", defaultValue = null),
            ColumnInfo("password_hash", "varchar(255)", "String", isPrimaryKey = false, isNullable = false, isAutoIncrement = false, comment = "Bcrypt hash", defaultValue = null),
            ColumnInfo("avatar", "varchar(500)", "String", isPrimaryKey = false, isNullable = true, isAutoIncrement = false, comment = "Avatar URL", defaultValue = null),
            ColumnInfo("age", "int4", "i32", isPrimaryKey = false, isNullable = true, isAutoIncrement = false, comment = "User age", defaultValue = null),
            ColumnInfo("is_active", "bool", "bool", isPrimaryKey = false, isNullable = false, isAutoIncrement = false, comment = "Active status", defaultValue = "true"),
            ColumnInfo("role", "varchar(20)", "String", isPrimaryKey = false, isNullable = false, isAutoIncrement = false, comment = "User role", defaultValue = "'user'",
                ext = mapOf("validation" to "enum(admin,user,guest)", "ui_hint" to "dropdown")),
            ColumnInfo("department_id", "int4", "i32", isPrimaryKey = false, isNullable = true, isAutoIncrement = false, comment = "FK to departments", defaultValue = null),
            ColumnInfo("created_at", "timestamptz", "DateTimeWithTimeZone", isPrimaryKey = false, isNullable = false, isAutoIncrement = false, comment = "Creation time", defaultValue = "now()"),
            ColumnInfo("updated_at", "timestamptz", "DateTimeWithTimeZone", isPrimaryKey = false, isNullable = false, isAutoIncrement = false, comment = "Last update", defaultValue = "now()"),
        ),
        primaryKeys = listOf("id")
    )

    /**
     * Sample FK relation for template preview.
     */
    private fun sampleRelations(): List<RelationInfo> = listOf(
        RelationInfo(RelationType.BELONGS_TO, "departments", "department_id", "id")
    )
}

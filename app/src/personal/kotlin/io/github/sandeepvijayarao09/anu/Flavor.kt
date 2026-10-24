package io.github.sandeepvijayarao09.anu

import android.content.Context
import io.github.sandeepvijayarao09.anu.connector.apps.AccessibilityAppDriver
import io.github.sandeepvijayarao09.anu.connector.sms.ProviderSms
import io.github.sandeepvijayarao09.anu.connector.sms.SmsSearchTool
import io.github.sandeepvijayarao09.anu.core.agent.AppStepTool
import io.github.sandeepvijayarao09.anu.core.agent.AppTaskTool
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.model.Brain
import java.time.ZoneId

/** The owner's build. It includes the SMS connector (ADR-012, ADR-032) and app control (ADR-098). */
internal object Flavor {
    const val HAS_SMS = true
    const val HAS_APP_CONTROL = true

    /** [appBrain] picks an app task's steps; [broker] runs them, the broker these tools join. */
    fun tools(context: Context, zone: () -> ZoneId, appBrain: () -> Brain?, broker: () -> ToolBroker): List<Tool<*, *>> {
        val apps = AccessibilityAppDriver(context)
        return listOf(SmsSearchTool(ProviderSms(context.contentResolver), zone), AppTaskTool(apps, appBrain, broker), AppStepTool(apps))
    }
}

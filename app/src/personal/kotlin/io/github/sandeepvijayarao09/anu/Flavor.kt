package io.github.sandeepvijayarao09.anu

import android.content.Context
import io.github.sandeepvijayarao09.anu.connector.sms.ProviderSms
import io.github.sandeepvijayarao09.anu.connector.sms.SmsSearchTool
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import java.time.ZoneId

/** The owner's build. It includes the SMS connector (ADR-012, ADR-032). */
internal object Flavor {
    const val HAS_SMS = true

    fun tools(context: Context, zone: () -> ZoneId): List<Tool<*, *>> = listOf(SmsSearchTool(ProviderSms(context.contentResolver), zone))
}

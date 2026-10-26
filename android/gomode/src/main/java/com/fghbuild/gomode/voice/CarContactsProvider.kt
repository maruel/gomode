// Shares only exported car contact cards with Android's contact importer.
package com.fghbuild.gomode.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.fghbuild.gomode.data.SettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CarContactsProvider : FileProvider()

internal data class CarContact(
    val name: String,
    val number: String,
    val website: String,
)

internal fun carContacts(settings: SettingsState): List<CarContact> =
    buildList {
        add(CarContact("Go Mode", GO_MODE_CAR_NUMBER, "https://gomode.dev"))
        for (server in settings.services) {
            val slot = settings.carCallerSlots[server.id] ?: continue
            add(CarContact(server.label, carCallerNumber(slot), server.url))
        }
    }

internal suspend fun carContactsIntent(
    context: Context,
    settings: SettingsState,
): Intent =
    withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "car-contacts")
        directory.mkdirs()
        // Separate files keep an in-progress import stable if another export is requested.
        val file = File.createTempFile("gomode-", ".vcf", directory)
        file.bufferedWriter().use { writer ->
            fun contact(
                name: String,
                number: String,
                website: String,
            ) {
                val escaped =
                    name
                        .replace("\\", "\\\\")
                        .replace("\r\n", "\n")
                        .replace('\r', '\n')
                        .replace("\n", "\\n")
                        .replace(";", "\\;")
                        .replace(",", "\\,")
                writer.write("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:$escaped\r\nN:$escaped;;;;\r\n")
                writer.write("TEL;TYPE=VOICE:${carNumberLabel(number)}\r\nNOTE:Created by Go Mode\r\n")
                // URL is a URI value; encode unsafe characters without changing its delimiters.
                writer.write("URL:${Uri.encode(website, ":/?#[]@!$&'()*+,;=%")}\r\nEND:VCARD\r\n")
            }
            for (entry in carContacts(settings)) {
                contact(entry.name, entry.number, entry.website)
            }
        }
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                FileProvider.getUriForFile(context, "${context.packageName}.carcontacts", file),
                "text/x-vcard",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

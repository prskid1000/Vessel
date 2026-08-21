package app.vessel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.vessel.data.ContainerCertificate
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VCaution
import app.vessel.ui.components.VIconAction
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VLabeledField
import app.vessel.ui.components.VRule
import app.vessel.ui.components.VTextField
import app.vessel.ui.theme.Vessel
import androidx.compose.material3.Text

/**
 * The certificate authorities this container trusts, on top of the ones Wine
 * ships with.
 *
 * **Why a container needs this at all.** A service reached over a private
 * network — a gateway on a tailnet, a proxy on the device itself — has no
 * certificate any public authority will sign, so it signs its own. Chromium then
 * refuses it, and an Electron application shows a connection error with no way in
 * from the outside: `--cacert` is curl's, `NODE_EXTRA_CA_CERTS` is Node's TLS and
 * misses Chromium's own network stack, and the network stack is what makes the
 * request that fails.
 *
 * What works is the root store, and on Windows that is the operating system's.
 * Wine imports every file in `WINE_ADDITIONAL_CERTS_DIR` into it at start-up, so
 * a certificate added here is trusted by everything in the container rather than
 * by whichever program happens to honour a flag.
 *
 * **Paste rather than a file picker.** The certificate is handed over as text —
 * copied from a settings page, printed by a server, pasted from a chat — and a
 * picker would demand it first be saved somewhere just to be read back.
 */
@Composable
fun CertificatesPanel(
    certificates: List<ContainerCertificate>,
    onAdd: (String) -> Boolean,
    onRemove: (ContainerCertificate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pasted by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11)) {
        Text(
            "Trusted by everything in this container. Take effect on the next start.",
            style = Vessel.type.monoSmall,
            color = Vessel.colors.textMuted,
        )

        if (certificates.isEmpty()) {
            Text(
                "None yet.",
                style = Vessel.type.body,
                color = Vessel.colors.textMuted,
            )
        } else {
            certificates.forEach { certificate ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            certificate.subject,
                            style = Vessel.type.body,
                            color = Vessel.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Valid to ${certificate.expires}",
                            style = Vessel.type.monoSmall,
                            color = Vessel.colors.textMuted,
                        )
                    }
                    VIconAction(
                        icon = VIcons.Trash,
                        contentDescription = "Stop trusting ${certificate.subject}",
                        onClick = { onRemove(certificate) },
                        style = VButtonStyle.Ghost,
                    )
                }
            }
        }

        VRule(verticalMargin = Vessel.metrics.s3)

        VLabeledField(label = "Paste a certificate") {
            VTextField(
                value = pasted,
                onValueChange = { next ->
                    pasted = next
                    refused = false
                },
                placeholder = "-----BEGIN CERTIFICATE-----",
            )
        }

        if (refused) {
            // Said here rather than swallowed. A certificate that is not one gets
            // written, imported by nobody, and shows up later as a TLS error
            // inside the guest with nothing to read but the error.
            VCaution("That is not a certificate. Copy the whole block, including both END lines.")
        }

        VButton(
            "Add",
            {
                if (onAdd(pasted)) {
                    pasted = ""
                    refused = false
                } else {
                    refused = true
                }
            },
            style = VButtonStyle.Primary,
            enabled = pasted.isNotBlank(),
            icon = VIcons.Plus,
        )
    }
}

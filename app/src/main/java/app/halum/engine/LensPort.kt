package app.halum.engine

import app.halum.core.LensProtocol
import app.halum.core.OcrBlock
import app.halum.core.lens.LensOcrClient

/**
 * D5: builds a real Lens request for a captured panel and decodes the answer.
 * The transport and client are injected so CI can use a replay transport.
 */
class LensPort(
    private val client: LensOcrClient,
    private val targetLanguage: String = "bn",
    private val region: String = "BD",
    private val timeZone: String = "Asia/Dhaka",
) : AndroidPanelPipeline.LensOcrClientPort {
    private var seq = 0

    override fun recognize(
        jpeg: ByteArray,
        width: Int,
        height: Int,
    ): List<OcrBlock> {
        val request =
            LensProtocol.Request(
                uuid = System.currentTimeMillis(),
                sequenceId = seq++,
                imageSequenceId = 0,
                width = width,
                height = height,
                targetLanguage = targetLanguage,
                region = region,
                timeZone = timeZone,
                jpeg = jpeg,
            )
        return client.recognize(request, width, height)
    }

    companion object {
        /** Host whose cookies Lens shares; used for the HttpTransport jar. */
        const val HOST = "https://lensfrontend-pa.googleapis.com"

        fun http(
            cookies: () -> String?,
            store: (String) -> Unit,
        ): LensPort = LensPort(LensOcrClient(HttpTransport(cookies = cookies, storeCookies = store)))

        fun replay(fixtures: List<ByteArray>): LensPort =
            LensPort(
                LensOcrClient(
                    app.halum.core.lens
                        .ReplayTransport(fixtures),
                ),
            )
    }
}

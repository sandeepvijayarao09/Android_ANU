package io.github.sandeepvijayarao09.anu.brain.litertlm

import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.Sampling

/**
 * Test speed's fixed request (ADR-073): a made-up passage long enough to time reading, and a short summary to time
 * writing. Greedy sampling and a fixed limit keep runs comparable. Nothing personal goes in.
 */
object SpeedTest {
    private const val PASSAGE =
        "The harbor town of Westmere kept its lighthouse lit for two hundred years. The first keeper, a carpenter named " +
            "Elias Brandt, built the tower from local stone and carried the oil up its ninety steps every evening. His " +
            "daughter Mara took over when his knees failed, and she kept a logbook of every ship that passed: the fishing " +
            "boats at dawn, the ferries twice a day, and the cargo ships that stayed far out beyond the reef. In the winter " +
            "of the great storm, the lamp's glass cracked, and Mara held a lantern in the gallery all night so the ferry " +
            "could find the channel. The town gave her a silver medal, which she kept in a drawer with her father's tools. " +
            "When electricity reached Westmere, the council voted to automate the light, and the last keeper moved into a " +
            "cottage at the foot of the hill. Today the tower is a small museum. Visitors climb the ninety steps, read " +
            "pages from Mara's logbook behind glass, and look out at the same reef the ships once feared. On clear nights " +
            "the automated lamp still turns every twelve seconds, and the town still counts its beats like a heartbeat."

    val request = BrainRequest(
        transcript = listOf(ChatMessage.User("$PASSAGE\n\nSummarize this story in about eighty words.")),
        sampling = Sampling.Precise,
        maxOutputTokens = 128,
    )
}

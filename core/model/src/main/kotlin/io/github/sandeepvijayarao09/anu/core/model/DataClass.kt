package io.github.sandeepvijayarao09.anu.core.model

/** How sensitive a piece of data is. Decides which tiers may see it (ARCHITECTURE §12.1). */
enum class DataClass {
    /** S0: no personal data. Any tier. */
    GENERAL,

    /** S1: names, schedules, email and message content. Cloud only with consent and minimization. */
    PERSONAL,

    /** S2: health, finance, precise location, IDs, intimate content. On-device unless consented per request. */
    SENSITIVE,

    /** S3: credentials. Never in any model context, on-device or cloud (I1). */
    SECRET,
}

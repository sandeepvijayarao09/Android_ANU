package io.github.sandeepvijayarao09.anu.core.model

/**
 * Kinds of content that other people can write. The model reads them as data, never as instructions (P4).
 * While the model can still see one, Anu asks before acting on its own, and the approval sheet names the
 * kind, never the content itself (§7.2).
 */
enum class UntrustedContent {
    EMAIL,
    MESSAGE,

    /** Event titles and notes, which others can write through invites. */
    CALENDAR,

    /** A store's catalog results (ADR-047). */
    STORE,

    /** A reply from an MCP service the user added (ADR-046). */
    SERVICE,

    /** A result from a tool that is no longer there, such as a removed MCP server's. */
    UNKNOWN,

    /** A photo the user attached: it can hold other people's words, such as a screenshot of a message (G2). */
    PHOTO,

    /** A recording written down on the phone, a voice note or a shared one: it can hold other people's words (G3). */
    RECORDING,

    /** The screen the user asked about from the assistant card: it can show other people's words (EXP-2). */
    SCREEN,
}

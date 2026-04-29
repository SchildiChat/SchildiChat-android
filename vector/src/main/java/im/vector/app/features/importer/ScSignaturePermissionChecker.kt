package im.vector.app.features.importer

val SC_ALLOWED_APP_SIGNATURES = listOf(
    // Stable
    ApplicationFingerprint(
        "chat.schildi.android",
        listOf(
            "66:12:AD:E7:E9:31:74:A5:89:CF:5B:A2:6E:D3:AB:28:23:1A:78:96:40:54:6C:8F:30:37:5E:F0:45:BC:92:42",
        ),
    ),
    // Testing
    ApplicationFingerprint(
        "chat.schildi.next",
        listOf(
            "C0:E0:77:3D:94:81:D5:A1:05:11:3A:04:EC:F5:1F:94:51:C0:20:89:7D:86:7D:9F:5F:EB:CE:B8:42:E9:DC:15",
        ),
    ),
    // Internal
    ApplicationFingerprint(
        "chat.schildi.next.internal",
        listOf(
            "C3:BC:B3:5C:29:98:A7:93:D4:29:55:5B:97:A4:C3:4F:E6:0A:B5:23:7E:CC:AE:35:04:B8:3F:08:E3:0B:47:9D",
        ),
    ),
)

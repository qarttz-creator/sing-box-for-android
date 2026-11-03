# libs/

`libbox.aar` (the sing-box native core, built with gomobile) is **not** committed here —
it is produced by CI from pinned sing-box sources and copied into this folder before the
app is assembled. See `.github/workflows/android.yml`.

To build locally, drop a `libbox.aar` (sing-box `make lib_android` output) into this folder.

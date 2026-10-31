# ScreenShare

A minimal, dependency-free screen sharing suite for Android TV: cast media from your phone and mirror your phone screen to your TV — no vendor lock-in, no ads, no accounts.

```
        ScreenShare
     share your screens
```

## Components

| Module | What it is | Size |
|---|---|---|
| [`sender/`](sender/) | Android phone app: discovers TVs on the LAN, pushes video/photo/music, mirrors the phone screen (H.264 + MPEG-TS over HTTP) | ~1 MB APK |
| [`tv/`](tv/) | Android TV receiver app: SSDP/DLNA renderer + SOAP control point + ExoPlayer playback | in progress |
| [`design/`](design/) | Brand key visuals and icon source renders | — |
| [`docs/`](docs/) | Protocol implementation notes (SSDP, UPnP AVTransport, MPEG-TS muxing) | — |

## Why

Commercial cast apps bundle ads, login walls, content-control backdoors and ~200 MB of
third-party SDKs. The actual protocol surface needed is small and standards-based:

- **Discovery**: SSDP `M-SEARCH` (multicast) + TCP subnet scan fallback (for AP-isolated routers)
- **Media push**: UPnP AVTransport SOAP (`SetAVTransportURI`, `Play`, `Pause`, `Stop`, `Seek`)
- **Screen mirroring**: `MediaProjection` → `MediaCodec` H.264 → self-written MPEG-TS muxer →
  `http://<phone>:8080/live.ts` → standard `SetAVTransportURI` to the TV

Everything above is public, documented protocol. No private SDKs, no cloud, no accounts.

## Sender features

- Auto-discovery (SSDP) with subnet-scan fallback and manual IP / device memory
- Cast local video / photo / music from the phone (streamed through an on-device HTTP relay with Range/seek support)
- Screen mirroring with audio (MediaProjection + MediaCodec AAC/H.264 into one MPEG-TS stream)
- Cast an online video by URL
- Playback controls: pause / stop / seek ±30 s
- ~1 MB APK, zero third-party dependencies

## Build

```bash
cd sender
gradle assembleRelease        # Android Gradle Plugin 8.5.2, JDK 17, compileSdk 34
```

## Status

- [x] Sender: discovery, media push, URL casting, seek, screen mirror with audio
- [ ] Receiver: standard reimplementation (in progress — `tv/`)
- [ ] PIN-code / passphrase connection (designed, see `docs/`)

## License

MIT — see [LICENSE](LICENSE).

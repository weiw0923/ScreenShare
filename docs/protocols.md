# Protocol implementation notes

Notes on the public standards used by ScreenShare. No third-party private
protocol is involved; everything below is documented in public specs.

## 1. Device discovery (SSDP)

Part of UPnP 1.0 (ISO/IEC 29341). The sender multicasts an `M-SEARCH` on
`239.255.255.250:1900` with `ST: urn:schemas-upnp-org:device:MediaRenderer:1`.
Receivers answer with a `LOCATION` header pointing at their device description
XML, which advertises an `AVTransport` service with a `controlURL`.

Fallback: some routers filter multicast (AP isolation) or place phones and TVs
on different subnets. The sender then probes TCP port 49152 across the local
/24 and validates candidates by fetching their description XML.

## 2. Media push (UPnP AVTransport)

Standard SOAP over HTTP POST to the renderer's `controlURL`:

```
SetAVTransportURI(InstanceID, CurrentURI, CurrentURIMetaData)
Play(InstanceID, Speed)
Pause / Stop / Seek(Unit=REL_TIME, Target=h:mm:ss)
GetPositionInfo → RelTime / TrackDuration
```

Local files are streamed by an on-device HTTP server (with `Range` support)
so the TV fetches bytes straight from the phone; content type is derived from
the file extension.

## 3. Screen mirroring (MPEG-TS over HTTP)

Public building blocks only:

1. `MediaProjection` creates a `VirtualDisplay` (screen capture)
2. `MediaCodec` encodes H.264 (`video/avc`) from the display Surface;
   `AudioRecord` + `MediaCodec` encode AAC (`audio/mp4a-latm`) from the mic
3. A hand-written MPEG-TS muxer (ISO/IEC 13818-1) packetizes both:
   - 188-byte TS packets, PAT (PID 0) + PMT (PID 0x1000) with CRC-32/MPEG
   - H.264 on PID 0x100 (stream type 0x1b), AAC ADTS on PID 0x101 (0x0F)
   - PES headers with PTS (90 kHz clock), adaptation-field stuffing,
     PCR + random-access flag on keyframes, AUD + SPS/PPS prepended on IDR
4. A tiny HTTP server exposes the live stream at `/live.ts`
5. The sender pushes `http://<phone-ip>:8080/live.ts` to the TV with plain
   `SetAVTransportURI`; any standards-compliant renderer can play it

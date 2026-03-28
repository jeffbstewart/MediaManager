# iOS TODO

## Multicast Networking Entitlement

Apple granted the multicast networking capability. Three steps to enable it:

1. **Create entitlements file** — In Xcode: app target → Signing & Capabilities → "+ Capability" → "Multicast Networking". This adds `com.apple.developer.networking.multicast = true` to the entitlements file.

2. **Add `NSLocalNetworkUsageDescription` to Info.plist** — Currently missing. Without it, iOS silently blocks local network access. The app already does SSDP multicast discovery (`SsdpDiscovery.swift` → `239.255.255.250:1900`) but never gets the permission prompt.
   ```xml
   <key>NSLocalNetworkUsageDescription</key>
   <string>Media Manager uses your local network to discover and connect to your media server.</string>
   ```

3. **Regenerate provisioning profile** — The new entitlement must be reflected in the provisioning profile. Either regenerate on the Apple Developer portal or let Xcode automatic signing handle it.

`NSBonjourServices` is NOT needed — current discovery is raw SSDP via `Network.framework`, not Bonjour.
